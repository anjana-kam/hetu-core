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
package io.hetu.core.plugin.iceberg;

import com.google.common.collect.ImmutableList;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.session.PropertyMetadata;
import io.prestosql.spi.type.ArrayType;

import javax.inject.Inject;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.hetu.core.plugin.iceberg.IcebergConfig.FORMAT_VERSION_SUPPORT_MAX;
import static io.hetu.core.plugin.iceberg.IcebergConfig.FORMAT_VERSION_SUPPORT_MIN;
import static io.prestosql.spi.StandardErrorCode.INVALID_TABLE_PROPERTY;
import static io.prestosql.spi.session.PropertyMetadata.enumProperty;
import static io.prestosql.spi.session.PropertyMetadata.integerProperty;
import static io.prestosql.spi.session.PropertyMetadata.stringProperty;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;

public class IcebergTableProperties
{
    public static final String FILE_FORMAT_PROPERTY = "format";
    public static final String PARTITIONING_PROPERTY = "partitioning";
    public static final String LOCATION_PROPERTY = "location";
    public static final String FORMAT_VERSION_PROPERTY = "format_version";

    private final List<PropertyMetadata<?>> tableProperties;

    @Inject
    public IcebergTableProperties(IcebergConfig icebergConfig)
    {
        tableProperties = ImmutableList.<PropertyMetadata<?>>builder()
                .add(integerProperty(
                        FORMAT_VERSION_PROPERTY,
                        "Iceberg table format version",
                        icebergConfig.getFormatVersion(),
                        IcebergTableProperties::validateFormatVersion,
                        false))
                .add(enumProperty(
                        FILE_FORMAT_PROPERTY,
                        "File format for the table",
                        IcebergFileFormat.class,
                        icebergConfig.getFileFormat(),
                        false))
                .add(new PropertyMetadata<>(
                        PARTITIONING_PROPERTY,
                        "Partition transforms",
                        new ArrayType(VARCHAR),
                        List.class,
                        ImmutableList.of(),
                        false,
                        value -> ((Collection<?>) value).stream()
                                .map(name -> ((String) name).toLowerCase(ENGLISH))
                                .collect(toImmutableList()),
                        value -> value))
                .add(stringProperty(
                        LOCATION_PROPERTY,
                        "File system location URI for the table",
                        null,
                        false))
                .build();
    }

    private static void validateFormatVersion(int version)
    {
        if (version < FORMAT_VERSION_SUPPORT_MIN || version > FORMAT_VERSION_SUPPORT_MAX) {
            throw new PrestoException(INVALID_TABLE_PROPERTY,
                    format("format_version must be between %d and %d", FORMAT_VERSION_SUPPORT_MIN, FORMAT_VERSION_SUPPORT_MAX));
        }
    }

    public List<PropertyMetadata<?>> getTableProperties()
    {
        return tableProperties;
    }

    public static IcebergFileFormat getFileFormat(Map<String, Object> tableProperties)
    {
        return (IcebergFileFormat) tableProperties.get(FILE_FORMAT_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    public static List<String> getPartitioning(Map<String, Object> tableProperties)
    {
        List<String> partitioning = (List<String>) tableProperties.get(PARTITIONING_PROPERTY);
        return partitioning == null ? ImmutableList.of() : ImmutableList.copyOf(partitioning);
    }

    public static Optional<String> getTableLocation(Map<String, Object> tableProperties)
    {
        return Optional.ofNullable((String) tableProperties.get(LOCATION_PROPERTY));
    }
}
