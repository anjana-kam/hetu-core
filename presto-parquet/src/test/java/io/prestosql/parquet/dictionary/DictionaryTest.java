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
package io.prestosql.parquet.dictionary;

import io.prestosql.parquet.ParquetEncoding;
import org.apache.parquet.io.api.Binary;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;

import static org.testng.Assert.assertThrows;

public class DictionaryTest
{
    private Dictionary dictionaryUnderTest;

    @BeforeMethod
    public void setUp() throws Exception
    {
        dictionaryUnderTest = new Dictionary(ParquetEncoding.PLAIN) {
        };
    }

    @Test
    public void testGetEncoding()
    {
        // Run the test
        ParquetEncoding encoding = dictionaryUnderTest.getEncoding();
    }

    @Test
    public void testDecodeToBinary()
    {
        // Setup
        final Binary expectedResult = Binary.fromReusedByteArray("content".getBytes(StandardCharsets.UTF_8));
        // Verify the results
        assertThrows(UnsupportedOperationException.class, () -> dictionaryUnderTest.decodeToBinary(0));
    }

    @Test
    public void testDecodeToInt()
    {
        assertThrows(UnsupportedOperationException.class, () -> dictionaryUnderTest.decodeToInt(0));
    }

    @Test
    public void testDecodeToLong()
    {
        assertThrows(UnsupportedOperationException.class, () -> dictionaryUnderTest.decodeToLong(0));
    }

    @Test
    public void testDecodeToFloat()
    {
        assertThrows(UnsupportedOperationException.class, () -> dictionaryUnderTest.decodeToFloat(0));
    }

    @Test
    public void testDecodeToDouble()
    {
        assertThrows(UnsupportedOperationException.class, () -> dictionaryUnderTest.decodeToFloat(0));
    }
}
