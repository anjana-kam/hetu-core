/*
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
package io.hetu.core.plugin.iceberg.delete;

import io.airlift.json.JsonCodec;
import io.airlift.slice.Slice;
import io.hetu.core.plugin.iceberg.CommitTaskData;
import io.hetu.core.plugin.iceberg.IcebergFileFormat;
import io.hetu.core.plugin.iceberg.IcebergFileWriter;
import io.hetu.core.plugin.iceberg.IcebergFileWriterFactory;
import io.hetu.core.plugin.iceberg.MetricsWrapper;
import io.hetu.core.plugin.iceberg.PartitionData;
import io.prestosql.plugin.hive.HdfsEnvironment;
import io.prestosql.plugin.hive.HdfsEnvironment.HdfsContext;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.RunLengthEncodedBlock;
import io.prestosql.spi.connector.ConnectorPageSink;
import io.prestosql.spi.connector.ConnectorSession;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionSpecParser;
import org.apache.iceberg.io.LocationProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static io.prestosql.plugin.hive.util.ConfigurationUtils.toJobConf;
import static io.prestosql.spi.predicate.Utils.nativeValueToBlock;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class IcebergPositionDeletePageSink
        implements ConnectorPageSink
{
    private final String dataFilePath;
    private final PartitionSpec partitionSpec;
    private final Optional<PartitionData> partition;
    private final String outputPath;
    private final JsonCodec<CommitTaskData> jsonCodec;
    private final IcebergFileWriter writer;
    private final IcebergFileFormat fileFormat;
    private final long fileRecordCount;

    private long validationCpuNanos;
    private boolean writtenData;
    private long deletedRowCount;

    public IcebergPositionDeletePageSink(
            String dataFilePath,
            PartitionSpec partitionSpec,
            Optional<PartitionData> partition,
            LocationProvider locationProvider,
            IcebergFileWriterFactory fileWriterFactory,
            HdfsEnvironment hdfsEnvironment,
            HdfsContext hdfsContext,
            JsonCodec<CommitTaskData> jsonCodec,
            ConnectorSession session,
            IcebergFileFormat fileFormat,
            Map<String, String> storageProperties,
            long fileRecordCount)
    {
        this.dataFilePath = requireNonNull(dataFilePath, "dataFilePath is null");
        this.jsonCodec = requireNonNull(jsonCodec, "jsonCodec is null");
        this.partitionSpec = requireNonNull(partitionSpec, "partitionSpec is null");
        this.partition = requireNonNull(partition, "partition is null");
        this.fileFormat = requireNonNull(fileFormat, "fileFormat is null");
        this.fileRecordCount = fileRecordCount;
        // prepend query id to a file name so we can determine which files were written by which query. This is needed for opportunistic cleanup of extra files
        // which may be present for successfully completing query in presence of failure recovery mechanisms.
        String fileName = fileFormat.toIceberg().addExtension(session.getQueryId() + "-" + randomUUID());
        this.outputPath = partition
                .map(partitionData -> locationProvider.newDataLocation(partitionSpec, partitionData, fileName))
                .orElseGet(() -> locationProvider.newDataLocation(fileName));
        JobConf jobConf = toJobConf(hdfsEnvironment.getConfiguration(hdfsContext, new Path(outputPath)));
        this.writer = fileWriterFactory.createPositionDeleteWriter(new Path(outputPath), jobConf, session, hdfsContext, fileFormat, storageProperties);
    }

    @Override
    public long getCompletedBytes()
    {
        return writer.getWrittenBytes();
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return writer.getSystemMemoryUsage();
    }

    @Override
    public long getValidationCpuNanos()
    {
        return validationCpuNanos;
    }

    @Override
    public CompletableFuture<?> appendPage(Page page)
    {
        checkArgument(page.getChannelCount() == 1, "IcebergPositionDeletePageSink expected a Page with only one channel, but got " + page.getChannelCount());

        Block[] blocks = new Block[2];
        blocks[0] = new RunLengthEncodedBlock(nativeValueToBlock(VARCHAR, utf8Slice(dataFilePath)), page.getPositionCount());
        blocks[1] = page.getBlock(0);
        writer.appendRows(new Page(blocks));

        writtenData = true;
        deletedRowCount += page.getPositionCount();
        return NOT_BLOCKED;
    }

    @Override
    public CompletableFuture<Collection<Slice>> finish()
    {
        Collection<Slice> commitTasks = new ArrayList<>();
        if (writtenData) {
            writer.commit();
            CommitTaskData task = new CommitTaskData(
                    outputPath,
                    fileFormat,
                    writer.getWrittenBytes(),
                    new MetricsWrapper(writer.getMetrics()),
                    PartitionSpecParser.toJson(partitionSpec),
                    partition.map(PartitionData::toJson),
                    FileContent.POSITION_DELETES,
                    Optional.of(dataFilePath),
                    Optional.of(fileRecordCount),
                    Optional.of(deletedRowCount));
            Long recordCount = task.getMetrics().recordCount();
            if (recordCount != null && recordCount > 0) {
                commitTasks.add(wrappedBuffer(jsonCodec.toJsonBytes(task)));
            }
            validationCpuNanos = writer.getValidationCpuNanos();
        }
        else {
            // clean up the empty delete file
            writer.rollback();
        }
        return completedFuture(commitTasks);
    }

    @Override
    public void abort()
    {
        writer.rollback();
    }
}
