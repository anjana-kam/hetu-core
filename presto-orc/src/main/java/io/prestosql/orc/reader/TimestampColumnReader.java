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
package io.prestosql.orc.reader;

import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.orc.OrcColumn;
import io.prestosql.orc.OrcCorruptionException;
import io.prestosql.orc.TupleDomainFilter;
import io.prestosql.orc.metadata.ColumnEncoding;
import io.prestosql.orc.metadata.ColumnMetadata;
import io.prestosql.orc.stream.BooleanInputStream;
import io.prestosql.orc.stream.InputStreamSource;
import io.prestosql.orc.stream.InputStreamSources;
import io.prestosql.orc.stream.LongInputStream;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.LongArrayBlock;
import io.prestosql.spi.block.RunLengthEncodedBlock;
import io.prestosql.spi.type.TimestampType;
import io.prestosql.spi.type.TimestampWithTimeZoneType;
import io.prestosql.spi.type.Type;
import org.joda.time.DateTimeZone;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.airlift.slice.SizeOf.sizeOf;
import static io.prestosql.orc.metadata.Stream.StreamKind.DATA;
import static io.prestosql.orc.metadata.Stream.StreamKind.PRESENT;
import static io.prestosql.orc.metadata.Stream.StreamKind.SECONDARY;
import static io.prestosql.orc.reader.ReaderUtils.verifyStreamType;
import static io.prestosql.orc.stream.MissingInputStreamSource.missingStreamSource;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static java.util.Objects.requireNonNull;

public class TimestampColumnReader
        implements ColumnReader<Long>
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(TimestampColumnReader.class).instanceSize();

    private static final int MILLIS_PER_SECOND = 1000;

    private final OrcColumn column;

    private long baseTimestampInSeconds;
    private DateTimeZone fileDateTimeZone;

    private int readOffset;
    private int nextBatchSize;

    private InputStreamSource<BooleanInputStream> presentStreamSource = missingStreamSource(BooleanInputStream.class);
    @Nullable
    private BooleanInputStream presentStream;
    private boolean[] nullVector = new boolean[0];

    private InputStreamSource<LongInputStream> secondsStreamSource = missingStreamSource(LongInputStream.class);
    @Nullable
    private LongInputStream secondsStream;

    private InputStreamSource<LongInputStream> nanosStreamSource = missingStreamSource(LongInputStream.class);
    @Nullable
    private LongInputStream nanosStream;

    private long[] secondsVector = new long[0];
    private long[] nanosVector = new long[0];

    private boolean rowGroupOpen;

    private final LocalMemoryContext systemMemoryContext;

    public TimestampColumnReader(Type type, OrcColumn column, LocalMemoryContext systemMemoryContext)
            throws OrcCorruptionException
    {
        requireNonNull(type, "type is null");
        //verifyStreamType(column, type, TimestampType.class::isInstance);
        verifyStreamType(column, type, t -> t instanceof TimestampType || t instanceof TimestampWithTimeZoneType);

        this.column = requireNonNull(column, "column is null");
        this.systemMemoryContext = requireNonNull(systemMemoryContext, "systemMemoryContext is null");
    }

    @Override
    public void prepareNextRead(int batchSize)
    {
        readOffset += nextBatchSize;
        nextBatchSize = batchSize;
    }

    @Override
    public Block readBlock()
            throws IOException
    {
        if (!rowGroupOpen) {
            openRowGroup();
        }

        if (readOffset > 0) {
            if (presentStream != null) {
                // skip ahead the present bit reader, but count the set bits
                // and use this as the skip size for the data reader
                readOffset = presentStream.countBitsSet(readOffset);
            }
            if (readOffset > 0) {
                if (secondsStream == null) {
                    throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is not null but seconds stream is missing");
                }
                if (nanosStream == null) {
                    throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is not null but nanos stream is missing");
                }

                secondsStream.skip(readOffset);
                nanosStream.skip(readOffset);
            }
        }

        Block block;
        if (secondsStream == null && nanosStream == null) {
            if (presentStream == null) {
                throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is null but present stream is missing");
            }
            presentStream.skip(nextBatchSize);
            block = RunLengthEncodedBlock.create(TIMESTAMP, null, nextBatchSize);
        }
        else if (presentStream == null) {
            block = readNonNullBlock();
        }
        else {
            boolean[] isNull = new boolean[nextBatchSize];
            int nullCount = presentStream.getUnsetBits(nextBatchSize, isNull);
            if (nullCount == 0) {
                block = readNonNullBlock();
            }
            else if (nullCount != nextBatchSize) {
                block = readNullBlock(isNull);
            }
            else {
                block = RunLengthEncodedBlock.create(TIMESTAMP, null, nextBatchSize);
            }
        }

        readOffset = 0;
        nextBatchSize = 0;
        return block;
    }

    private Block readNonNullBlock()
            throws IOException
    {
        if (secondsStream == null) {
            throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is not null but seconds stream is missing");
        }
        if (nanosStream == null) {
            throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is not null but nanos stream is missing");
        }

        long[] values = new long[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            values[i] = decodeTimestamp(secondsStream.next(), nanosStream.next(), baseTimestampInSeconds);
        }
        if (fileDateTimeZone != DateTimeZone.UTC) {
            for (int i = 0; i < nextBatchSize; i++) {
                values[i] = fileDateTimeZone.convertUTCToLocal(values[i]);
            }
        }
        return new LongArrayBlock(nextBatchSize, Optional.empty(), values);
    }

    private Block readNullBlock(boolean[] isNull)
            throws IOException
    {
        if (secondsStream == null) {
            throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is not null but seconds stream is missing");
        }
        if (nanosStream == null) {
            throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is not null but nanos stream is missing");
        }

        long[] values = new long[isNull.length];
        for (int i = 0; i < isNull.length; i++) {
            if (!isNull[i]) {
                values[i] = decodeTimestamp(secondsStream.next(), nanosStream.next(), baseTimestampInSeconds);
            }
        }
        if (fileDateTimeZone != DateTimeZone.UTC) {
            for (int i = 0; i < nextBatchSize; i++) {
                if (!isNull[i]) {
                    values[i] = fileDateTimeZone.convertUTCToLocal(values[i]);
                }
            }
        }
        return new LongArrayBlock(isNull.length, Optional.of(isNull), values);
    }

    private void openRowGroup()
            throws IOException
    {
        presentStream = presentStreamSource.openStream();
        secondsStream = secondsStreamSource.openStream();
        nanosStream = nanosStreamSource.openStream();

        rowGroupOpen = true;
    }

    @Override
    public void startStripe(ZoneId fileTimeZone, InputStreamSources dictionaryStreamSources, ColumnMetadata<ColumnEncoding> encoding)
    {
        baseTimestampInSeconds = ZonedDateTime.of(2015, 1, 1, 0, 0, 0, 0, fileTimeZone).toEpochSecond();

        fileDateTimeZone = DateTimeZone.forID(fileTimeZone.getId());
        presentStreamSource = missingStreamSource(BooleanInputStream.class);
        secondsStreamSource = missingStreamSource(LongInputStream.class);
        nanosStreamSource = missingStreamSource(LongInputStream.class);

        readOffset = 0;
        nextBatchSize = 0;

        presentStream = null;
        secondsStream = null;
        nanosStream = null;

        rowGroupOpen = false;
    }

    @Override
    public void startRowGroup(InputStreamSources dataStreamSources)
    {
        presentStreamSource = dataStreamSources.getInputStreamSource(column, PRESENT, BooleanInputStream.class);
        secondsStreamSource = dataStreamSources.getInputStreamSource(column, DATA, LongInputStream.class);
        nanosStreamSource = dataStreamSources.getInputStreamSource(column, SECONDARY, LongInputStream.class);

        readOffset = 0;
        nextBatchSize = 0;

        presentStream = null;
        secondsStream = null;
        nanosStream = null;

        rowGroupOpen = false;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .addValue(column)
                .toString();
    }

    // This comes from the Apache Hive ORC code
    private static long decodeTimestamp(long seconds, long serializedNanos, long baseTimestampInSeconds)
    {
        long millis = (seconds + baseTimestampInSeconds) * MILLIS_PER_SECOND;
        long nanos = parseNanos(serializedNanos);
        if (nanos > 999999999 || nanos < 0) {
            throw new IllegalArgumentException("nanos field of an encoded timestamp in ORC must be between 0 and 999999999 inclusive, got " + nanos);
        }

        // the rounding error exists because java always rounds up when dividing integers
        // -42001/1000 = -42; and -42001 % 1000 = -1 (+ 1000)
        // to get the correct value we need
        // (-42 - 1)*1000 + 999 = -42001
        // (42)*1000 + 1 = 42001
        if (millis < 0 && nanos != 0) {
            millis -= 1000;
        }
        // Truncate nanos to millis and add to mills
        return millis + (nanos / 1_000_000);
    }

    // This comes from the Apache Hive ORC code
    private static int parseNanos(long serialized)
    {
        int zeros = ((int) serialized) & 0b111;
        int result = (int) (serialized >>> 3);
        if (zeros != 0) {
            for (int i = 0; i <= zeros; ++i) {
                result *= 10;
            }
        }
        return result;
    }

    @Override
    public void close()
    {
        systemMemoryContext.close();
        nullVector = null;
        secondsVector = null;
        nanosVector = null;
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE + sizeOf(nullVector) + sizeOf(secondsVector) + sizeOf(nanosVector);
    }

    @Override
    public boolean filterTest(TupleDomainFilter filter, Long value)
    {
        if (value == null) {
            return filter.testNull();
        }

        return filter.testLong(value);
    }
}
