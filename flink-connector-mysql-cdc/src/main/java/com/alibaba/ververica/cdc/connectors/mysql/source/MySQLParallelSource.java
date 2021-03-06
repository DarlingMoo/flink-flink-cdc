/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.ververica.cdc.connectors.mysql.source;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.synchronization.FutureCompletingBlockingQueue;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.table.types.logical.RowType;

import com.alibaba.ververica.cdc.connectors.mysql.debezium.offset.BinlogPosition;
import com.alibaba.ververica.cdc.connectors.mysql.source.assigner.MySQLSnapshotSplitAssigner;
import com.alibaba.ververica.cdc.connectors.mysql.source.enumerator.MySQLSourceEnumState;
import com.alibaba.ververica.cdc.connectors.mysql.source.enumerator.MySQLSourceEnumStateSerializer;
import com.alibaba.ververica.cdc.connectors.mysql.source.enumerator.MySQLSourceEnumerator;
import com.alibaba.ververica.cdc.connectors.mysql.source.reader.MySQLRecordEmitter;
import com.alibaba.ververica.cdc.connectors.mysql.source.reader.MySQLSourceReader;
import com.alibaba.ververica.cdc.connectors.mysql.source.split.MySQLSplit;
import com.alibaba.ververica.cdc.connectors.mysql.source.split.MySQLSplitReader;
import com.alibaba.ververica.cdc.connectors.mysql.source.split.MySQLSplitSerializer;
import com.alibaba.ververica.cdc.debezium.DebeziumDeserializationSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Supplier;

import static com.alibaba.ververica.cdc.connectors.mysql.source.MySQLSourceOptions.getServerIdForSubTask;

/**
 * The MySQL CDC Source based on FLIP-27 and Watermark Signal Algorithms which supports parallel
 * reading snapshot of table and then continue to capture data change from binlog.
 *
 * <pre>
 *     1. The source supports parallel capturing table change.
 *     2. The source supports checkpoint in split level when read snapshot data.
 *     3. The source does need apply any lock of MySQL.
 * </pre>
 *
 * @param <T> The record type.
 */
public class MySQLParallelSource<T>
        implements Source<T, MySQLSplit, MySQLSourceEnumState>, ResultTypeQueryable<T> {

    private final RowType splitKeyRowType;
    private final DebeziumDeserializationSchema<T> deserializationSchema;
    private final Configuration config;

    public MySQLParallelSource(
            RowType splitKeyRowType,
            DebeziumDeserializationSchema<T> deserializationSchema,
            Configuration config) {
        this.splitKeyRowType = splitKeyRowType;
        this.deserializationSchema = deserializationSchema;
        this.config = config;
    }

    @Override
    public Boundedness getBoundedness() {
        return Boundedness.CONTINUOUS_UNBOUNDED;
    }

    @Override
    public SourceReader<T, MySQLSplit> createReader(SourceReaderContext readerContext)
            throws Exception {
        FutureCompletingBlockingQueue<RecordsWithSplitIds<Tuple2<T, BinlogPosition>>>
                elementsQueue = new FutureCompletingBlockingQueue<>();

        // set the server id for reader
        Configuration readerConfiguration = config.clone();
        readerConfiguration.removeConfig(MySQLSourceOptions.SERVER_ID);
        readerConfiguration.setString(
                "database.server.id",
                getServerIdForSubTask(config, readerContext.getIndexOfSubtask()));

        Supplier<MySQLSplitReader> splitReaderSupplier =
                () -> new MySQLSplitReader(readerConfiguration, readerContext.getIndexOfSubtask());
        return new MySQLSourceReader(
                elementsQueue,
                splitReaderSupplier,
                new MySQLRecordEmitter(deserializationSchema),
                readerConfiguration,
                readerContext);
    }

    @Override
    public SplitEnumerator<MySQLSplit, MySQLSourceEnumState> createEnumerator(
            SplitEnumeratorContext<MySQLSplit> enumContext) throws Exception {
        final MySQLSnapshotSplitAssigner splitAssigner =
                new MySQLSnapshotSplitAssigner(
                        config, this.splitKeyRowType, new ArrayList<>(), new ArrayList<>());
        return new MySQLSourceEnumerator(
                enumContext, splitAssigner, new HashMap<>(), new HashMap<>());
    }

    @Override
    public SplitEnumerator<MySQLSplit, MySQLSourceEnumState> restoreEnumerator(
            SplitEnumeratorContext<MySQLSplit> enumContext, MySQLSourceEnumState checkpoint)
            throws Exception {
        final MySQLSnapshotSplitAssigner splitAssigner =
                new MySQLSnapshotSplitAssigner(
                        config,
                        this.splitKeyRowType,
                        checkpoint.getAlreadyProcessedTables(),
                        checkpoint.getRemainingSplits());
        return new MySQLSourceEnumerator(
                enumContext,
                splitAssigner,
                checkpoint.getAssignedSplits(),
                checkpoint.getFinishedSnapshotSplits());
    }

    @Override
    public SimpleVersionedSerializer<MySQLSplit> getSplitSerializer() {
        return MySQLSplitSerializer.INSTANCE;
    }

    @Override
    public SimpleVersionedSerializer<MySQLSourceEnumState> getEnumeratorCheckpointSerializer() {
        return new MySQLSourceEnumStateSerializer(getSplitSerializer());
    }

    @Override
    public TypeInformation<T> getProducedType() {
        return deserializationSchema.getProducedType();
    }
}
