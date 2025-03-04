/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.threadpool;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.elasticsearch.test.hamcrest.RegexMatcher;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;

@ClusterScope(scope = Scope.TEST, numDataNodes = 0, numClientNodes = 0)
public class SimpleThreadPoolIT extends ESIntegTestCase {
    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder().build();
    }

    public void testThreadNames() throws Exception {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        Set<String> preNodeStartThreadNames = new HashSet<>();
        for (long l : threadBean.getAllThreadIds()) {
            ThreadInfo threadInfo = threadBean.getThreadInfo(l);
            if (threadInfo != null) {
                preNodeStartThreadNames.add(threadInfo.getThreadName());
            }
        }
        logger.info("pre node threads are {}", preNodeStartThreadNames);
        internalCluster().startNode();
        logger.info("do some indexing, flushing, optimize, and searches");
        int numDocs = randomIntBetween(2, 100);
        IndexRequestBuilder[] builders = new IndexRequestBuilder[numDocs];
        for (int i = 0; i < numDocs; ++i) {
            builders[i] = client().prepareIndex("idx")
                .setSource(
                    jsonBuilder().startObject()
                        .field("str_value", "s" + i)
                        .array("str_values", new String[] { "s" + (i * 2), "s" + (i * 2 + 1) })
                        .field("l_value", i)
                        .array("l_values", new int[] { i * 2, i * 2 + 1 })
                        .field("d_value", i)
                        .array("d_values", new double[] { i * 2, i * 2 + 1 })
                        .endObject()
                );
        }
        indexRandom(true, builders);
        int numSearches = randomIntBetween(2, 100);
        for (int i = 0; i < numSearches; i++) {
            assertNoFailures(client().prepareSearch("idx").setQuery(QueryBuilders.termQuery("str_value", "s" + i)));
            assertNoFailures(client().prepareSearch("idx").setQuery(QueryBuilders.termQuery("l_value", i)));
        }
        Set<String> threadNames = new HashSet<>();
        for (long l : threadBean.getAllThreadIds()) {
            ThreadInfo threadInfo = threadBean.getThreadInfo(l);
            if (threadInfo != null) {
                threadNames.add(threadInfo.getThreadName());
            }
        }
        logger.info("post node threads are {}", threadNames);
        threadNames.removeAll(preNodeStartThreadNames);
        logger.info("post node *new* threads are {}", threadNames);
        for (String threadName : threadNames) {
            // ignore some shared threads we know that are created within the same VM, like the shared discovery one
            // or the ones that are occasionally come up from ESSingleNodeTestCase
            if (threadName.contains("[node_s_0]") // TODO: this can't possibly be right! single node and integ test are unrelated!
                || threadName.contains("Keep-Alive-Timer")
                || threadName.contains("readiness-service")
                || threadName.contains("JVMCI-native") // GraalVM Compiler Thread
                || threadName.contains("file-watcher[") // AbstractFileWatchingService
                || threadName.contains("FileSystemWatch")) { // FileSystemWatchService(Linux/Windows), FileSystemWatcher(BSD/AIX)
                continue;
            }
            String nodePrefix = "("
                + Pattern.quote(ESIntegTestCase.SUITE_CLUSTER_NODE_PREFIX)
                + "|"
                + Pattern.quote(ESIntegTestCase.TEST_CLUSTER_NODE_PREFIX)
                + ")";
            assertThat(threadName, RegexMatcher.matches("\\[" + nodePrefix + "\\d+\\]"));
        }
    }

}
