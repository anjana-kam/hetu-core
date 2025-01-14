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
package io.hetu.core.plugin.mariadb.optimization;

import io.prestosql.plugin.jdbc.BaseJdbcConfig;
import io.prestosql.plugin.jdbc.optimization.BaseJdbcQueryGenerator;
import io.prestosql.plugin.jdbc.optimization.JdbcPushDownParameter;
import io.prestosql.plugin.jdbc.optimization.JdbcQueryGeneratorContext;
import io.prestosql.spi.function.FunctionMetadataManager;
import io.prestosql.spi.function.StandardFunctionResolution;
import io.prestosql.spi.plan.GroupIdNode;
import io.prestosql.spi.plan.PlanVisitor;
import io.prestosql.spi.relation.DeterminismEvaluator;
import io.prestosql.spi.relation.RowExpressionService;
import io.prestosql.spi.type.TypeManager;

import java.util.Optional;

public class MariaQueryGenerator
        extends BaseJdbcQueryGenerator
{
    public MariaQueryGenerator(DeterminismEvaluator determinismEvaluator, RowExpressionService rowExpressionService, FunctionMetadataManager functionManager, StandardFunctionResolution functionResolution, JdbcPushDownParameter pushDownParameter, BaseJdbcConfig baseConfig)
    {
        super(pushDownParameter, new MariaRowExpressionConverter(determinismEvaluator, rowExpressionService, functionManager, functionResolution, baseConfig), new MariaSqlStatementWriter(pushDownParameter));
    }

    @Override
    protected PlanVisitor<Optional<JdbcQueryGeneratorContext>, Void> getVisitor(TypeManager typeManager)
    {
        return new MariaPlanVisitor(typeManager);
    }

    protected class MariaPlanVisitor
            extends BaseJdbcPlanVisitor
    {
        MariaPlanVisitor(TypeManager typeManager)
        {
            super(typeManager);
        }

        @Override
        public Optional<JdbcQueryGeneratorContext> visitGroupId(GroupIdNode node, Void contextIn)
        {
            // MariaDB is not support grouping sets expression, don't push down this node
            return Optional.empty();
        }
    }
}
