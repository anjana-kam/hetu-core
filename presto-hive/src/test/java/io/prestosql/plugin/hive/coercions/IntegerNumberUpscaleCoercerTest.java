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
package io.prestosql.plugin.hive.coercions;

import io.prestosql.plugin.hive.ReaderProjectionsAdapterTest;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.BigintType;
import io.prestosql.spi.type.Type;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class IntegerNumberUpscaleCoercerTest<F extends Type, T extends Type>
{
    private IntegerNumberUpscaleCoercer<F, T> integerNumberUpscaleCoercerUnderTest;

    @BeforeMethod
    public void setUp() throws Exception
    {
        integerNumberUpscaleCoercerUnderTest = new IntegerNumberUpscaleCoercer(BigintType.BIGINT, BigintType.BIGINT);
    }

    @Test
    public void testApplyCoercedValue()
    {
        // Setup
        final BlockBuilder blockBuilder = null;
        final Block block = new ReaderProjectionsAdapterTest.Block();

        // Run the test
        integerNumberUpscaleCoercerUnderTest.applyCoercedValue(blockBuilder, block, 0);

        // Verify the results
    }
}
