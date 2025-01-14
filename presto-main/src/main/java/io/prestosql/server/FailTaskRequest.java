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
package io.prestosql.server;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.prestosql.execution.ExecutionFailureInfo;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

public class FailTaskRequest
{
    private final ExecutionFailureInfo failureInfo;

    @JsonCreator
    public FailTaskRequest(
            @JsonProperty("failureInfo") ExecutionFailureInfo failureInfo)
    {
        this.failureInfo = requireNonNull(failureInfo, "failureInfo is null");
    }

    @JsonProperty
    public ExecutionFailureInfo getFailureInfo()
    {
        return failureInfo;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("failureInfo", failureInfo)
                .toString();
    }
}
