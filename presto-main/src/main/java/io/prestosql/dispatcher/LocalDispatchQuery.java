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
package io.prestosql.dispatcher;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.log.Logger;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.execution.ClusterSizeMonitor;
import io.prestosql.execution.ExecutionFailureInfo;
import io.prestosql.execution.QueryExecution;
import io.prestosql.execution.QueryState;
import io.prestosql.execution.QueryStateMachine;
import io.prestosql.execution.StateMachine.StateChangeListener;
import io.prestosql.server.BasicQueryInfo;
import io.prestosql.spi.ErrorCode;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.resourcegroups.ResourceGroupId;
import org.joda.time.DateTime;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.google.common.util.concurrent.Futures.nonCancellationPropagating;
import static io.airlift.concurrent.MoreFutures.addExceptionCallback;
import static io.airlift.concurrent.MoreFutures.addSuccessCallback;
import static io.airlift.concurrent.MoreFutures.tryGetFutureValue;
import static io.airlift.units.DataSize.Unit.BYTE;
import static io.prestosql.execution.QueryState.FAILED;
import static io.prestosql.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.prestosql.util.Failures.toFailure;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class LocalDispatchQuery
        implements DispatchQuery
{
    private static final Logger log = Logger.get(LocalDispatchQuery.class);
    private final QueryStateMachine stateMachine;
    private final ListenableFuture<QueryExecution> queryExecutionFuture;

    private final ClusterSizeMonitor clusterSizeMonitor;

    private final Executor queryExecutor;

    private final Consumer<QueryExecution> querySubmitter;
    private final SettableFuture<?> submitted = SettableFuture.create();
    private AtomicInteger retryCount = new AtomicInteger();

    public LocalDispatchQuery(
            QueryStateMachine stateMachine,
            ListenableFuture<QueryExecution> queryExecutionFuture,
            ClusterSizeMonitor clusterSizeMonitor,
            Executor queryExecutor,
            Consumer<QueryExecution> querySubmitter)
    {
        this.stateMachine = requireNonNull(stateMachine, "stateMachine is null");
        this.queryExecutionFuture = requireNonNull(queryExecutionFuture, "queryExecutionFuture is null");
        this.clusterSizeMonitor = requireNonNull(clusterSizeMonitor, "clusterSizeMonitor is null");
        this.queryExecutor = requireNonNull(queryExecutor, "queryExecutor is null");
        this.querySubmitter = requireNonNull(querySubmitter, "querySubmitter is null");

        addExceptionCallback(queryExecutionFuture, stateMachine::transitionToFailed);
        stateMachine.addStateChangeListener(state -> {
            if (state.isDone()) {
                submitted.set(null);
            }
        });
    }

    @Override
    public void startWaitingForResources()
    {
        if (stateMachine.transitionToWaitingForResources()) {
            waitForMinimumWorkers();
        }
    }

    private void waitForMinimumWorkers()
    {
        ListenableFuture<?> minimumWorkerFuture = clusterSizeMonitor.waitForMinimumWorkers();
        // when worker requirement is met, wait for query execution to finish construction and then start the execution
        addSuccessCallback(minimumWorkerFuture, () -> addSuccessCallback(queryExecutionFuture, this::startExecution));
        addExceptionCallback(minimumWorkerFuture, throwable -> queryExecutor.execute(() -> stateMachine.transitionToFailed(throwable)));
    }

    private void startExecution(QueryExecution queryExecution)
    {
        queryExecution.setTryCount(retryCount.getAndIncrement());
        queryExecutor.execute(() -> {
            if (stateMachine.transitionToDispatching()) {
                try {
                    querySubmitter.accept(queryExecution);
                }
                catch (Throwable t) {
                    // this should never happen but be safe
                    stateMachine.transitionToFailed(t);
                    log.error(t, "query submitter threw exception");
                    throw t;
                }
                finally {
                    submitted.set(null);
                }
            }
        });
    }

    @Override
    public void recordHeartbeat()
    {
        stateMachine.recordHeartbeat();
    }

    @Override
    public DateTime getLastHeartbeat()
    {
        return stateMachine.getLastHeartbeat();
    }

    @Override
    public ListenableFuture<?> getDispatchedFuture()
    {
        return nonCancellationPropagating(submitted);
    }

    @Override
    public DispatchInfo getDispatchInfo()
    {
        // observe submitted before getting the state, to ensure a failed query stat is visible
        boolean dispatched = submitted.isDone();
        BasicQueryInfo queryInfo = stateMachine.getBasicQueryInfo(Optional.empty());

        if (queryInfo.getState() == FAILED) {
            ExecutionFailureInfo failureInfo = stateMachine.getFailureInfo()
                    .orElseGet(() -> toFailure(new PrestoException(GENERIC_INTERNAL_ERROR, "Query failed for an unknown reason")));
            return DispatchInfo.failed(failureInfo, queryInfo.getQueryStats().getElapsedTime(), queryInfo.getQueryStats().getQueuedTime());
        }
        if (dispatched) {
            return DispatchInfo.dispatched(new LocalCoordinatorLocation(), queryInfo.getQueryStats().getElapsedTime(), queryInfo.getQueryStats().getQueuedTime());
        }
        return DispatchInfo.queued(queryInfo.getQueryStats().getElapsedTime(), queryInfo.getQueryStats().getQueuedTime());
    }

    @Override
    public QueryId getQueryId()
    {
        return stateMachine.getQueryId();
    }

    public ResourceGroupId getResourceGroup()
    {
        return stateMachine.getResourceGroup();
    }

    @Override
    public boolean isDone()
    {
        return stateMachine.getQueryState().isDone();
    }

    @Override
    public DateTime getCreateTime()
    {
        return stateMachine.getCreateTime();
    }

    @Override
    public Optional<DateTime> getExecutionStartTime()
    {
        return stateMachine.getExecutionStartTime();
    }

    @Override
    public Optional<DateTime> getQueryExecutionStartTime()
    {
        return stateMachine.getExecutionStartTime();
    }

    @Override
    public OptionalDouble getQueryProgress()
    {
        return getBasicQueryInfo().getQueryStats().getProgressPercentage();
    }

    @Override
    public Optional<DateTime> getEndTime()
    {
        return stateMachine.getEndTime();
    }

    @Override
    public Duration getTotalCpuTime()
    {
        return tryGetQueryExecution()
                .map(QueryExecution::getTotalCpuTime)
                .orElse(new Duration(0, MILLISECONDS));
    }

    @Override
    public DataSize getTotalMemoryReservation()
    {
        return tryGetQueryExecution()
                .map(QueryExecution::getTotalMemoryReservation)
                .orElse(new DataSize(0, BYTE));
    }

    @Override
    public DataSize getUserMemoryReservation()
    {
        return tryGetQueryExecution()
                .map(QueryExecution::getUserMemoryReservation)
                .orElse(new DataSize(0, BYTE));
    }

    @Override
    public BasicQueryInfo getBasicQueryInfo()
    {
        return tryGetQueryExecution()
                .map(QueryExecution::getBasicQueryInfo)
                .orElse(stateMachine.getBasicQueryInfo(Optional.empty()));
    }

    @Override
    public Session getSession()
    {
        return stateMachine.getSession();
    }

    @Override
    public void fail(Throwable throwable)
    {
        stateMachine.transitionToFailed(throwable);
    }

    @Override
    public void cancel()
    {
        stateMachine.transitionToCanceled();
    }

    @Override
    public void pruneInfo()
    {
        stateMachine.pruneQueryInfo();
    }

    @Override
    public Optional<ErrorCode> getErrorCode()
    {
        return stateMachine.getFailureInfo().map(ExecutionFailureInfo::getErrorCode);
    }

    @Override
    public void addStateChangeListener(StateChangeListener<QueryState> stateChangeListener)
    {
        stateMachine.addStateChangeListener(stateChangeListener);
    }

    private Optional<QueryExecution> tryGetQueryExecution()
    {
        try {
            return tryGetFutureValue(queryExecutionFuture);
        }
        catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public int getRetryCount()
    {
        return retryCount.get();
    }
}
