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
package io.prestosql.spi.connector;

import io.prestosql.spi.WarningCode;
import io.prestosql.spi.WarningCodeSupplier;

public enum StandardWarningCode
        implements WarningCodeSupplier
{
    TOO_MANY_STAGES(0x0000_0001),
    REDUNDANT_ORDER_BY(0x0000_0002),
    EXPIRED_CUBE(0x0000_0003),
    CUBE_NOT_FOUND(0x0000_0004),
    PARSER_WARNING(0x0000_0005),
    SNAPSHOT_NOT_SUPPORTED(0x0000_0006),
    SNAPSHOT_RECOVERY(0X000_007),
    TASK_RETRY_NOT_SUPPORTED(0x0000_0008),
    CTE_REUSE_NOT_SUPPORTED(0x0000_0009),
    REUSE_TABLE_SCAN_NOT_SUPPORTED(0x0000_0010),
    CTE_RESULT_CACHE_NOT_SUPPORTED(0x0000_0009)
    /**/;
    private final WarningCode warningCode;

    StandardWarningCode(int code)
    {
        warningCode = new WarningCode(code, name());
    }

    @Override
    public WarningCode toWarningCode()
    {
        return warningCode;
    }
}
