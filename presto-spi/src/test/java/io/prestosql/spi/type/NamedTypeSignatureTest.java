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
package io.prestosql.spi.type;

import org.mockito.Mock;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class NamedTypeSignatureTest
{
    @Mock
    private TypeSignature mockTypeSignature;

    private NamedTypeSignature namedTypeSignatureUnderTest;

    @BeforeMethod
    public void setUp() throws Exception
    {
        initMocks(this);
        namedTypeSignatureUnderTest = new NamedTypeSignature(
                Optional.of(new RowFieldName("name", false)), mockTypeSignature);
    }

    @Test
    public void testGetName() throws Exception
    {
        // Setup
        // Run the test
        final Optional<String> result = namedTypeSignatureUnderTest.getName();

        // Verify the results
        assertEquals(Optional.of("value"), result);
    }

    @Test
    public void testEquals() throws Exception
    {
        assertTrue(namedTypeSignatureUnderTest.equals("o"));
    }

    @Test
    public void testToString() throws Exception
    {
        // Setup
        when(mockTypeSignature.toString()).thenReturn("result");

        // Run the test
        final String result = namedTypeSignatureUnderTest.toString();

        // Verify the results
        assertEquals("typeSignature", result);
    }

    @Test
    public void testHashCode() throws Exception
    {
        assertEquals(0, namedTypeSignatureUnderTest.hashCode());
    }
}