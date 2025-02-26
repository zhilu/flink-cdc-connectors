/*
 * Copyright 2023 Ververica Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.base.source;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.connector.base.source.reader.RecordEmitter;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.synchronization.FutureCompletingBlockingQueue;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.util.FlinkRuntimeException;

import com.ververica.cdc.common.annotation.Experimental;
import com.ververica.cdc.common.annotation.VisibleForTesting;
import com.ververica.cdc.connectors.base.config.SourceConfig;
import com.ververica.cdc.connectors.base.dialect.DataSourceDialect;
import com.ververica.cdc.connectors.base.source.assigner.HybridSplitAssigner;
import com.ververica.cdc.connectors.base.source.assigner.SplitAssigner;
import com.ververica.cdc.connectors.base.source.assigner.StreamSplitAssigner;
import com.ververica.cdc.connectors.base.source.assigner.state.HybridPendingSplitsState;
import com.ververica.cdc.connectors.base.source.assigner.state.PendingSplitsState;
import com.ververica.cdc.connectors.base.source.assigner.state.PendingSplitsStateSerializer;
import com.ververica.cdc.connectors.base.source.assigner.state.StreamPendingSplitsState;
import com.ververica.cdc.connectors.base.source.enumerator.IncrementalSourceEnumerator;
import com.ververica.cdc.connectors.base.source.meta.offset.OffsetFactory;
import com.ververica.cdc.connectors.base.source.meta.split.SourceRecords;
import com.ververica.cdc.connectors.base.source.meta.split.SourceSplitBase;
import com.ververica.cdc.connectors.base.source.meta.split.SourceSplitSerializer;
import com.ververica.cdc.connectors.base.source.meta.split.SourceSplitState;
import com.ververica.cdc.connectors.base.source.metrics.SourceReaderMetrics;
import com.ververica.cdc.connectors.base.source.reader.IncrementalSourceReader;
import com.ververica.cdc.connectors.base.source.reader.IncrementalSourceRecordEmitter;
import com.ververica.cdc.connectors.base.source.reader.IncrementalSourceSplitReader;
import com.ververica.cdc.connectors.base.source.utils.hooks.SnapshotPhaseHooks;
import com.ververica.cdc.debezium.DebeziumDeserializationSchema;
import io.debezium.relational.TableId;

import java.util.List;
import java.util.function.Supplier;

/**
 * The basic source of Incremental Snapshot framework for datasource, it is based on FLIP-27 and
 * Watermark Signal Algorithm which supports parallel reading snapshot of table and then continue to
 * capture data change by streaming reading.
 */
@Experimental
public class IncrementalSource<T, C extends SourceConfig>
        implements Source<T, SourceSplitBase, PendingSplitsState>, ResultTypeQueryable<T> {

    private static final long serialVersionUID = 1L;

    protected final SourceConfig.Factory<C> configFactory;
    protected final DataSourceDialect<C> dataSourceDialect;
    protected final OffsetFactory offsetFactory;
    protected final DebeziumDeserializationSchema<T> deserializationSchema;
    protected final SourceSplitSerializer sourceSplitSerializer;

    // Actions to perform during the snapshot phase.
    // This field is introduced for testing purpose, for example testing if changes made in the
    // snapshot phase are correctly backfilled into the snapshot by registering a pre high watermark
    // hook for generating changes.
    private SnapshotPhaseHooks snapshotHooks = SnapshotPhaseHooks.empty();

    public IncrementalSource(
            SourceConfig.Factory<C> configFactory,
            DebeziumDeserializationSchema<T> deserializationSchema,
            OffsetFactory offsetFactory,
            DataSourceDialect<C> dataSourceDialect) {
        this.configFactory = configFactory;
        this.deserializationSchema = deserializationSchema;
        this.offsetFactory = offsetFactory;
        this.dataSourceDialect = dataSourceDialect;
        this.sourceSplitSerializer =
                new SourceSplitSerializer() {
                    @Override
                    public OffsetFactory getOffsetFactory() {
                        return offsetFactory;
                    }
                };
    }

    @Override
    public Boundedness getBoundedness() {
        C sourceConfig = configFactory.create(0);
        if (sourceConfig.getStartupOptions().isSnapshotOnly()) {
            return Boundedness.BOUNDED;
        } else {
            return Boundedness.CONTINUOUS_UNBOUNDED;
        }
    }

    @Override
    public IncrementalSourceReader<T, C> createReader(SourceReaderContext readerContext)
            throws Exception {
        // create source config for the given subtask (e.g. unique server id)
        C sourceConfig = configFactory.create(readerContext.getIndexOfSubtask());
        FutureCompletingBlockingQueue<RecordsWithSplitIds<SourceRecords>> elementsQueue =
                new FutureCompletingBlockingQueue<>();

        final SourceReaderMetrics sourceReaderMetrics =
                new SourceReaderMetrics(readerContext.metricGroup());

        sourceReaderMetrics.registerMetrics();
        Supplier<IncrementalSourceSplitReader<C>> splitReaderSupplier =
                () ->
                        new IncrementalSourceSplitReader<>(
                                readerContext.getIndexOfSubtask(),
                                dataSourceDialect,
                                sourceConfig,
                                snapshotHooks);
        return new IncrementalSourceReader<>(
                elementsQueue,
                splitReaderSupplier,
                createRecordEmitter(sourceConfig, sourceReaderMetrics),
                readerContext.getConfiguration(),
                readerContext,
                sourceConfig,
                sourceSplitSerializer,
                dataSourceDialect);
    }

    @Override
    public SplitEnumerator<SourceSplitBase, PendingSplitsState> createEnumerator(
            SplitEnumeratorContext<SourceSplitBase> enumContext) {
        C sourceConfig = configFactory.create(0);
        final SplitAssigner splitAssigner;
        if (!sourceConfig.getStartupOptions().isStreamOnly()) {
            try {
                final List<TableId> remainingTables =
                        dataSourceDialect.discoverDataCollections(sourceConfig);
                boolean isTableIdCaseSensitive =
                        dataSourceDialect.isDataCollectionIdCaseSensitive(sourceConfig);
                splitAssigner =
                        new HybridSplitAssigner<>(
                                sourceConfig,
                                enumContext.currentParallelism(),
                                remainingTables,
                                isTableIdCaseSensitive,
                                dataSourceDialect,
                                offsetFactory);
            } catch (Exception e) {
                throw new FlinkRuntimeException(
                        "Failed to discover captured tables for enumerator", e);
            }
        } else {
            splitAssigner = new StreamSplitAssigner(sourceConfig, dataSourceDialect, offsetFactory);
        }

        return new IncrementalSourceEnumerator(
                enumContext, sourceConfig, splitAssigner, getBoundedness());
    }

    @Override
    public SplitEnumerator<SourceSplitBase, PendingSplitsState> restoreEnumerator(
            SplitEnumeratorContext<SourceSplitBase> enumContext, PendingSplitsState checkpoint) {
        C sourceConfig = configFactory.create(0);

        final SplitAssigner splitAssigner;
        if (checkpoint instanceof HybridPendingSplitsState) {
            splitAssigner =
                    new HybridSplitAssigner<>(
                            sourceConfig,
                            enumContext.currentParallelism(),
                            (HybridPendingSplitsState) checkpoint,
                            dataSourceDialect,
                            offsetFactory);
        } else if (checkpoint instanceof StreamPendingSplitsState) {
            splitAssigner =
                    new StreamSplitAssigner(
                            sourceConfig,
                            (StreamPendingSplitsState) checkpoint,
                            dataSourceDialect,
                            offsetFactory);
        } else {
            throw new UnsupportedOperationException(
                    "Unsupported restored PendingSplitsState: " + checkpoint);
        }
        return new IncrementalSourceEnumerator(
                enumContext, sourceConfig, splitAssigner, getBoundedness());
    }

    @Override
    public SimpleVersionedSerializer<SourceSplitBase> getSplitSerializer() {
        return sourceSplitSerializer;
    }

    @Override
    public SimpleVersionedSerializer<PendingSplitsState> getEnumeratorCheckpointSerializer() {
        SourceSplitSerializer sourceSplitSerializer = (SourceSplitSerializer) getSplitSerializer();
        return new PendingSplitsStateSerializer(sourceSplitSerializer);
    }

    @Override
    public TypeInformation<T> getProducedType() {
        return deserializationSchema.getProducedType();
    }

    protected RecordEmitter<SourceRecords, T, SourceSplitState> createRecordEmitter(
            SourceConfig sourceConfig, SourceReaderMetrics sourceReaderMetrics) {
        return new IncrementalSourceRecordEmitter<>(
                deserializationSchema,
                sourceReaderMetrics,
                sourceConfig.isIncludeSchemaChanges(),
                offsetFactory);
    }

    /**
     * Set snapshot hooks only for test. The SnapshotPhaseHook should be serializable。
     *
     * @param snapshotHooks
     */
    @VisibleForTesting
    public void setSnapshotHooks(SnapshotPhaseHooks snapshotHooks) {
        this.snapshotHooks = snapshotHooks;
    }
}
