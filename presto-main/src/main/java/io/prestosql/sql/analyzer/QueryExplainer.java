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
package io.prestosql.sql.analyzer;

import com.google.common.collect.ImmutableMap;
import io.prestosql.Session;
import io.prestosql.cache.CachedDataStorageProvider;
import io.prestosql.cost.CostCalculator;
import io.prestosql.cost.StatsCalculator;
import io.prestosql.cube.CubeManager;
import io.prestosql.execution.DataDefinitionTask;
import io.prestosql.execution.scheduler.NodeScheduler;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.heuristicindex.HeuristicIndexerManager;
import io.prestosql.metadata.Metadata;
import io.prestosql.security.AccessControl;
import io.prestosql.snapshot.RecoveryUtils;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.plan.PlanNodeIdAllocator;
import io.prestosql.sql.parser.SqlParser;
import io.prestosql.sql.planner.LogicalPlanner;
import io.prestosql.sql.planner.Plan;
import io.prestosql.sql.planner.PlanFragmenter;
import io.prestosql.sql.planner.PlanOptimizers;
import io.prestosql.sql.planner.SubPlan;
import io.prestosql.sql.planner.TypeAnalyzer;
import io.prestosql.sql.planner.optimizations.PlanOptimizer;
import io.prestosql.sql.planner.planprinter.IoPlanPrinter;
import io.prestosql.sql.planner.planprinter.PlanPrinter;
import io.prestosql.sql.tree.ExplainType.Type;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.Statement;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.sql.planner.planprinter.IoPlanPrinter.textIoPlan;
import static java.util.Objects.requireNonNull;

public class QueryExplainer
{
    private final List<PlanOptimizer> planOptimizers;
    private final PlanFragmenter planFragmenter;
    private final Metadata metadata;
    private final AccessControl accessControl;
    private final SqlParser sqlParser;
    private final StatsCalculator statsCalculator;
    private final CostCalculator costCalculator;
    private final Map<Class<? extends Statement>, DataDefinitionTask<?>> dataDefinitionTask;
    private final HeuristicIndexerManager heuristicIndexerManager;
    private final CubeManager cubeManager;
    private final RecoveryUtils recoveryUtils;
    private final NodeScheduler nodeScheduler;

    @Inject
    public QueryExplainer(
            PlanOptimizers planOptimizers,
            PlanFragmenter planFragmenter,
            Metadata metadata,
            AccessControl accessControl,
            SqlParser sqlParser,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            Map<Class<? extends Statement>, DataDefinitionTask<?>> dataDefinitionTask,
            HeuristicIndexerManager heuristicIndexerManager,
            CubeManager cubeManager,
            RecoveryUtils recoveryUtils,
            NodeScheduler nodeScheduler)
    {
        this(
                planOptimizers.get(),
                planFragmenter,
                metadata,
                accessControl,
                sqlParser,
                statsCalculator,
                costCalculator,
                dataDefinitionTask,
                heuristicIndexerManager,
                cubeManager, recoveryUtils, nodeScheduler);
    }

    public QueryExplainer(
            List<PlanOptimizer> planOptimizers,
            PlanFragmenter planFragmenter,
            Metadata metadata,
            AccessControl accessControl,
            SqlParser sqlParser,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            Map<Class<? extends Statement>, DataDefinitionTask<?>> dataDefinitionTask,
            HeuristicIndexerManager heuristicIndexerManager,
            CubeManager cubeManager,
            RecoveryUtils recoveryUtils,
            NodeScheduler nodeScheduler)
    {
        this.planOptimizers = requireNonNull(planOptimizers, "planOptimizers is null");
        this.planFragmenter = requireNonNull(planFragmenter, "planFragmenter is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.statsCalculator = requireNonNull(statsCalculator, "statsCalculator is null");
        this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
        this.dataDefinitionTask = ImmutableMap.copyOf(requireNonNull(dataDefinitionTask, "dataDefinitionTask is null"));
        this.heuristicIndexerManager = requireNonNull(heuristicIndexerManager, "heuristicIndexerManager is null");
        this.cubeManager = requireNonNull(cubeManager, "cubeManager is null");
        this.recoveryUtils = recoveryUtils;
        this.nodeScheduler = nodeScheduler;
    }

    public Analysis analyze(Session session, Statement statement, List<Expression> parameters, WarningCollector warningCollector)
    {
        Analyzer analyzer = new Analyzer(session, metadata, sqlParser, accessControl, Optional.of(this), parameters, warningCollector, heuristicIndexerManager, cubeManager);
        return analyzer.analyze(statement);
    }

    public String getPlan(Session session, Statement statement, Type planType, List<Expression> parameters, WarningCollector warningCollector)
    {
        DataDefinitionTask<?> task = dataDefinitionTask.get(statement.getClass());
        if (task != null) {
            return explainTask(statement, task, parameters);
        }

        switch (planType) {
            case LOGICAL:
                Plan plan = getLogicalPlan(session, statement, parameters, warningCollector);
                return PlanPrinter.textLogicalPlan(plan.getRoot(), plan.getTypes(), metadata, plan.getStatsAndCosts(), session, 0, false);
            case DISTRIBUTED:
                SubPlan subPlan = getDistributedPlan(session, statement, parameters, warningCollector);
                return PlanPrinter.textDistributedPlan(subPlan, metadata, session, false);
            case IO:
                return IoPlanPrinter.textIoPlan(getLogicalPlan(session, statement, parameters, warningCollector).getRoot(), metadata, session);
        }
        throw new IllegalArgumentException("Unhandled plan type: " + planType);
    }

    private static <T extends Statement> String explainTask(Statement statement, DataDefinitionTask<T> task, List<Expression> parameters)
    {
        return task.explain((T) statement, parameters);
    }

    public String getGraphvizPlan(Session session, Statement statement, Type planType, List<Expression> parameters, WarningCollector warningCollector)
    {
        DataDefinitionTask<?> task = dataDefinitionTask.get(statement.getClass());
        if (task != null) {
            // todo format as graphviz
            return explainTask(statement, task, parameters);
        }

        switch (planType) {
            case LOGICAL:
                Plan plan = getLogicalPlan(session, statement, parameters, warningCollector);
                return PlanPrinter.graphvizLogicalPlan(plan.getRoot(), plan.getTypes());
            case DISTRIBUTED:
                SubPlan subPlan = getDistributedPlan(session, statement, parameters, warningCollector);
                return PlanPrinter.graphvizDistributedPlan(subPlan);
        }
        throw new IllegalArgumentException("Unhandled plan type: " + planType);
    }

    public String getJsonPlan(Session session, Statement statement, Type planType, List<Expression> parameters, WarningCollector warningCollector)
    {
        DataDefinitionTask<?> task = dataDefinitionTask.get(statement.getClass());
        if (task != null) {
            // todo format as json
            return explainTask(statement, task, parameters);
        }

        switch (planType) {
            case IO:
                Plan plan = getLogicalPlan(session, statement, parameters, warningCollector);
                return textIoPlan(plan.getRoot(), metadata, session);
            default:
                throw new PrestoException(NOT_SUPPORTED, String.format("Unsupported explain plan type %s for JSON format", planType));
        }
    }

    public Plan getLogicalPlan(Session session, Statement statement, List<Expression> parameters, WarningCollector warningCollector)
    {
        // analyze statement
        Analysis analysis = analyze(session, statement, parameters, warningCollector);

        PlanNodeIdAllocator idAllocator = new PlanNodeIdAllocator();

        // plan statement
        LogicalPlanner logicalPlanner = new LogicalPlanner(session, planOptimizers, idAllocator, metadata, new TypeAnalyzer(sqlParser, metadata), statsCalculator, costCalculator, warningCollector, CachedDataStorageProvider.NULL_PROVIDER, recoveryUtils.getOrCreateQuerySnapshotManager(session.getQueryId(), session), nodeScheduler);
        return logicalPlanner.plan(analysis, false);
    }

    private SubPlan getDistributedPlan(Session session, Statement statement, List<Expression> parameters, WarningCollector warningCollector)
    {
        Plan plan = getLogicalPlan(session, statement, parameters, warningCollector);
        return planFragmenter.createSubPlans(session, plan, false, warningCollector);
    }
}
