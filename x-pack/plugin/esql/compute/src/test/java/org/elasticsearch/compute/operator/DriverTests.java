/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRunnable;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.SubscribableListener;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.compute.data.BasicBlockTests;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.FixedExecutorBuilder;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.equalTo;

public class DriverTests extends ESTestCase {

    public void testThreadContext() {
        DriverContext driverContext = driverContext();
        ThreadPool threadPool = threadPool();
        try {
            List<Page> inPages = randomList(1, 100, DriverTests::randomPage);
            List<Page> outPages = new ArrayList<>();
            WarningsOperator warning1 = new WarningsOperator(threadPool);
            WarningsOperator warning2 = new WarningsOperator(threadPool);
            Driver driver = new Driver(driverContext, new CannedSourceOperator(inPages.iterator()) {
                @Override
                public Page getOutput() {
                    assertRunningWithRegularUser(threadPool);
                    return super.getOutput();
                }
            }, List.of(warning1, new SwitchContextOperator(threadPool), warning2), new PageConsumerOperator(page -> {
                assertRunningWithRegularUser(threadPool);
                outPages.add(page);
            }), () -> {});
            ThreadContext threadContext = threadPool.getThreadContext();
            SubscribableListener<Void> future = new SubscribableListener<>();
            try (ThreadContext.StoredContext ignored = threadContext.stashContext()) {
                threadContext.putHeader("user", "user1");
                Driver.start(threadContext, threadPool.executor("esql"), driver, between(1, 1000), future);
            }
            future.addListener(ActionListener.running(() -> {
                assertRunningWithRegularUser(threadPool);
                assertThat(outPages, equalTo(inPages));
                Map<String, Set<String>> actualResponseHeaders = new HashMap<>();
                for (Map.Entry<String, List<String>> e : threadPool.getThreadContext().getResponseHeaders().entrySet()) {
                    actualResponseHeaders.put(e.getKey(), Sets.newHashSet(e.getValue()));
                }
                Map<String, Set<String>> expectedResponseHeaders = new HashMap<>(warning1.warnings);
                for (Map.Entry<String, Set<String>> e : warning2.warnings.entrySet()) {
                    expectedResponseHeaders.merge(e.getKey(), e.getValue(), Sets::union);
                }
                assertThat(actualResponseHeaders, equalTo(expectedResponseHeaders));
            }));
            PlainActionFuture<Void> completion = new PlainActionFuture<>();
            future.addListener(completion);
            completion.actionGet(TimeValue.timeValueSeconds(30));
        } finally {
            terminate(threadPool);
        }
    }

    private static void assertRunningWithRegularUser(ThreadPool threadPool) {
        String user = threadPool.getThreadContext().getHeader("user");
        assertThat(user, equalTo("user1"));
    }

    private static Page randomPage() {
        BasicBlockTests.RandomBlock block = BasicBlockTests.randomBlock(
            randomFrom(ElementType.BOOLEAN, ElementType.INT, ElementType.BYTES_REF),
            between(1, 10),
            randomBoolean(),
            1,
            between(1, 2),
            0,
            2
        );
        return new Page(block.block());
    }

    static class SwitchContextOperator extends AsyncOperator {
        private final ThreadPool threadPool;

        SwitchContextOperator(ThreadPool threadPool) {
            super(between(1, 3));
            this.threadPool = threadPool;
        }

        @Override
        protected void performAsync(Page page, ActionListener<Page> listener) {
            assertRunningWithRegularUser(threadPool);
            if (randomBoolean()) {
                listener.onResponse(page);
                return;
            }
            threadPool.schedule(ActionRunnable.wrap(listener, innerListener -> {
                try (ThreadContext.StoredContext ignored = threadPool.getThreadContext().stashContext()) {
                    threadPool.getThreadContext().putHeader("user", "system");
                    innerListener.onResponse(page);
                }
            }), TimeValue.timeValueNanos(100), threadPool.executor("esql"));
        }

        @Override
        public void close() {

        }
    }

    static class WarningsOperator extends AbstractPageMappingOperator {
        private final ThreadPool threadPool;
        private final Map<String, Set<String>> warnings = new HashMap<>();

        WarningsOperator(ThreadPool threadPool) {
            this.threadPool = threadPool;
        }

        @Override
        protected Page process(Page page) {
            assertRunningWithRegularUser(threadPool);
            if (randomInt(100) < 10) {
                String k = "header-" + between(1, 10);
                Set<String> vs = Sets.newHashSet(randomList(1, 2, () -> "value-" + between(1, 20)));
                warnings.merge(k, vs, Sets::union);
                for (String v : vs) {
                    threadPool.getThreadContext().addResponseHeader(k, v);
                }
            }
            return page;
        }

        @Override
        public String toString() {
            return "WarningsOperator";
        }

        @Override
        public void close() {

        }
    }

    private ThreadPool threadPool() {
        int numThreads = randomIntBetween(1, 10);
        return new TestThreadPool(
            getTestClass().getSimpleName(),
            new FixedExecutorBuilder(Settings.EMPTY, "esql", numThreads, 1024, "esql", EsExecutors.TaskTrackingConfig.DEFAULT)
        );
    }

    private DriverContext driverContext() {
        MockBigArrays bigArrays = new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, ByteSizeValue.ofGb(1));
        CircuitBreaker breaker = bigArrays.breakerService().getBreaker(CircuitBreaker.REQUEST);
        BlockFactory blockFactory = new BlockFactory(breaker, bigArrays);
        return new DriverContext(bigArrays, blockFactory);
    }

}
