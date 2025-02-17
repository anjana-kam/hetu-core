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

package io.prestosql.spi.statistics;

import io.prestosql.spi.connector.ColumnHandle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static java.lang.String.format;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;

public final class TableStatistics
{
    private static final TableStatistics EMPTY = TableStatistics.builder().build();

    private final Estimate rowCount;
    private final long fileCount;
    private final long onDiskDataSizeInBytes;
    private final Map<ColumnHandle, ColumnStatistics> columnStatistics;

    public static TableStatistics empty()
    {
        return EMPTY;
    }

    // added parameters fileCount and onDiskDataSizeInBytes used as check for invalidating tableStatisticsCache.
    public TableStatistics(Estimate rowCount, long fileCount, long onDiskDataSizeInBytes, Map<ColumnHandle, ColumnStatistics> columnStatistics)
    {
        this.rowCount = requireNonNull(rowCount, "rowCount can not be null");
        this.fileCount = requireNonNull(fileCount, "fileCount can not be null");
        this.onDiskDataSizeInBytes = requireNonNull(onDiskDataSizeInBytes, "onDiskDataSizeInBytes can not be null");
        if (!rowCount.isUnknown() && rowCount.getValue() < 0) {
            throw new IllegalArgumentException(format("rowCount must be greater than or equal to 0: %s", rowCount.getValue()));
        }
        this.columnStatistics = unmodifiableMap(requireNonNull(columnStatistics, "columnStatistics can not be null"));
    }

    public TableStatistics(Estimate rowCount, Map<ColumnHandle, ColumnStatistics> columnStatistics)
    {
        this.rowCount = requireNonNull(rowCount, "rowCount cannot be null");
        if (!rowCount.isUnknown() && rowCount.getValue() < 0) {
            throw new IllegalArgumentException(format("rowCount must be greater than or equal to 0: %s", rowCount.getValue()));
        }
        this.columnStatistics = unmodifiableMap(requireNonNull(columnStatistics, "columnStatistics cannot be null"));
        this.fileCount = 0L;
        this.onDiskDataSizeInBytes = 0L;
    }

    public Estimate getRowCount()
    {
        return rowCount;
    }

    public long getFileCount()
    {
        return fileCount;
    }

    public long getOnDiskDataSizeInBytes()
    {
        return onDiskDataSizeInBytes;
    }

    public Map<ColumnHandle, ColumnStatistics> getColumnStatistics()
    {
        return columnStatistics;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TableStatistics that = (TableStatistics) o;
        return Objects.equals(rowCount, that.rowCount) &&
                Objects.equals(columnStatistics, that.columnStatistics);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(rowCount, columnStatistics);
    }

    @Override
    public String toString()
    {
        return "TableStatistics{" +
                "rowCount=" + rowCount +
                ", columnStatistics=" + columnStatistics +
                '}';
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static final class Builder
    {
        private Estimate rowCount = Estimate.unknown();
        private long fileCount;
        private long onDiskDataSizeInBytes;
        private Map<ColumnHandle, ColumnStatistics> columnStatisticsMap = new LinkedHashMap<>();

        public Builder setRowCount(Estimate rowCount)
        {
            this.rowCount = requireNonNull(rowCount, "rowCount can not be null");
            return this;
        }

        public Builder setFileCount(long fileCount)
        {
            this.fileCount = requireNonNull(fileCount, "fileCount can not be null");
            return this;
        }

        public Builder setOnDiskDataSizeInBytes(long onDiskDataSizeInBytes)
        {
            this.onDiskDataSizeInBytes = requireNonNull(onDiskDataSizeInBytes, "onDiskDataSizeInBytes can not be null");
            return this;
        }

        public Builder setColumnStatistics(ColumnHandle columnHandle, ColumnStatistics columnStatistics)
        {
            requireNonNull(columnHandle, "columnHandle can not be null");
            requireNonNull(columnStatistics, "columnStatistics can not be null");
            this.columnStatisticsMap.put(columnHandle, columnStatistics);
            return this;
        }

        public TableStatistics build()
        {
            return new TableStatistics(rowCount, fileCount, onDiskDataSizeInBytes, columnStatisticsMap);
        }
    }
}
