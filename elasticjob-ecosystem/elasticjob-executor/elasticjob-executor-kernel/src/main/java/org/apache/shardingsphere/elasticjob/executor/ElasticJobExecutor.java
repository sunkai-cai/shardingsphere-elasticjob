/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.elasticjob.executor;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.elasticjob.api.ElasticJob;
import org.apache.shardingsphere.elasticjob.api.JobConfiguration;
import org.apache.shardingsphere.elasticjob.error.handler.JobErrorHandler;
import org.apache.shardingsphere.elasticjob.executor.context.ExecutorContext;
import org.apache.shardingsphere.elasticjob.executor.item.JobItemExecutor;
import org.apache.shardingsphere.elasticjob.executor.item.JobItemExecutorFactory;
import org.apache.shardingsphere.elasticjob.infra.env.IpUtils;
import org.apache.shardingsphere.elasticjob.infra.exception.ExceptionUtils;
import org.apache.shardingsphere.elasticjob.infra.exception.JobExecutionEnvironmentException;
import org.apache.shardingsphere.elasticjob.infra.listener.ShardingContexts;
import org.apache.shardingsphere.elasticjob.tracing.event.JobExecutionEvent;
import org.apache.shardingsphere.elasticjob.tracing.event.JobExecutionEvent.ExecutionSource;
import org.apache.shardingsphere.elasticjob.tracing.event.JobStatusTraceEvent.State;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * ElasticJob executor.
 * 任务执行者
 */
@Slf4j
public final class ElasticJobExecutor {

    /**
     * 任务对象
     */
    private final ElasticJob elasticJob;

    /**
     * 任务配置 门面模式
     */
    private final JobFacade jobFacade;

    /**
     * 任务执行器
     */
    private final JobItemExecutor jobItemExecutor;

    /**
     * ???
     */
    private final ExecutorContext executorContext;

    /**
     * ???
     */
    private final Map<Integer, String> itemErrorMessages;

    public ElasticJobExecutor(final ElasticJob elasticJob, final JobConfiguration jobConfig,
            final JobFacade jobFacade) {
        this(elasticJob, jobConfig, jobFacade, JobItemExecutorFactory.getExecutor(elasticJob.getClass()));
    }

    //无elasticJob的支持方案
    public ElasticJobExecutor(final String type, final JobConfiguration jobConfig, final JobFacade jobFacade) {
        this(null, jobConfig, jobFacade, JobItemExecutorFactory.getExecutor(type));
    }

    private ElasticJobExecutor(final ElasticJob elasticJob, final JobConfiguration jobConfig,
            final JobFacade jobFacade, final JobItemExecutor jobItemExecutor) {
        this.elasticJob = elasticJob;
        this.jobFacade = jobFacade;
        this.jobItemExecutor = jobItemExecutor;
        executorContext = new ExecutorContext(jobFacade.loadJobConfiguration(true));
        itemErrorMessages = new ConcurrentHashMap<>(jobConfig.getShardingTotalCount(), 1);
    }

    /**
     * Execute job.
     * 定时任务执行的入口.
     * 由 LiteJob 引导执行
     */
    public void execute() {
        //获取任务配置
        JobConfiguration jobConfig = jobFacade.loadJobConfiguration(true);
        //???
        executorContext.reloadIfNecessary(jobConfig);
        //从上下文中获取错误处理方式
        JobErrorHandler jobErrorHandler = executorContext.get(JobErrorHandler.class);
        try {
            //检查当前执行环境
            jobFacade.checkJobExecutionEnvironment();
        } catch (final JobExecutionEnvironmentException cause) {
            //???? 默认是什么？？
            jobErrorHandler.handleException(jobConfig.getJobName(), cause);
        }
        //获取分片信息
        //？？？如何分片的？？
        ShardingContexts shardingContexts = jobFacade.getShardingContexts();
        //抛出任务启动事件
        jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_STAGING, String.format("Job '%s' "
                + "execute begin.", jobConfig.getJobName()));
        //判断该任务是否执行中
        if (jobFacade.misfireIfRunning(shardingContexts.getShardingItemParameters().keySet())) {
            //抛出任务结束事件
            jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_FINISHED, String.format(
                    "Previous job '%s' - shardingItems '%s' is still running, misfired job will start after previous "
                            + "job completed.", jobConfig.getJobName(),
                    shardingContexts.getShardingItemParameters().keySet()));
            return;
        }
        try {
            //调用任务执行前监听器
            jobFacade.beforeJobExecuted(shardingContexts);
            //CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            //CHECKSTYLE:ON
            jobErrorHandler.handleException(jobConfig.getJobName(), cause);
        }
        //执行任务
        execute(jobConfig, shardingContexts, ExecutionSource.NORMAL_TRIGGER);
        //确认执行结束
        while (jobFacade.isExecuteMisfired(shardingContexts.getShardingItemParameters().keySet())) {
            jobFacade.clearMisfire(shardingContexts.getShardingItemParameters().keySet());
            //补偿执行
            execute(jobConfig, shardingContexts, ExecutionSource.MISFIRE);
        }
        //故障转移
        jobFacade.failoverIfNecessary();
        try {
            //调用任务执行后监听器
            jobFacade.afterJobExecuted(shardingContexts);
            //CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            //CHECKSTYLE:ON
            jobErrorHandler.handleException(jobConfig.getJobName(), cause);
        }
    }

    /**
     * 执行任务
     */
    private void execute(final JobConfiguration jobConfig, final ShardingContexts shardingContexts,
            final ExecutionSource executionSource) {
        if (shardingContexts.getShardingItemParameters().isEmpty()) {
            jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_FINISHED, String.format(
                    "Sharding item for job '%s' is empty.", jobConfig.getJobName()));
            return;
        }
        //记录任务开始
        jobFacade.registerJobBegin(shardingContexts);
        String taskId = shardingContexts.getTaskId();
        //抛出任务执行中事件
        jobFacade.postJobStatusTraceEvent(taskId, State.TASK_RUNNING, "");
        try {
            //执行
            process(jobConfig, shardingContexts, executionSource);
        } finally {
            // TODO Consider increasing the status of job failure, and how to handle the overall loop of job failure
            //记录任务完成
            jobFacade.registerJobCompleted(shardingContexts);
            if (itemErrorMessages.isEmpty()) {
                //抛出任务执行结束
                jobFacade.postJobStatusTraceEvent(taskId, State.TASK_FINISHED, "");
            } else {
                //抛出任务执行错误
                jobFacade.postJobStatusTraceEvent(taskId, State.TASK_ERROR, itemErrorMessages.toString());
            }
        }
    }

    private void process(final JobConfiguration jobConfig, final ShardingContexts shardingContexts,
            final ExecutionSource executionSource) {
        Collection<Integer> items = shardingContexts.getShardingItemParameters().keySet();
        if (1 == items.size()) {
            //只有一个分片的情况下
            int item = shardingContexts.getShardingItemParameters().keySet().iterator().next();
            //收集任务执行环境信息
            JobExecutionEvent jobExecutionEvent = new JobExecutionEvent(IpUtils.getHostName(), IpUtils.getIp(),
                    shardingContexts.getTaskId(), jobConfig.getJobName(), executionSource, item);
            process(jobConfig, shardingContexts, item, jobExecutionEvent);
            return;
        }

        //多个分片的情况下
        CountDownLatch latch = new CountDownLatch(items.size());
        for (int each : items) {
            JobExecutionEvent jobExecutionEvent = new JobExecutionEvent(IpUtils.getHostName(), IpUtils.getIp(),
                    shardingContexts.getTaskId(), jobConfig.getJobName(), executionSource, each);
            ExecutorService executorService = executorContext.get(ExecutorService.class);
            if (executorService.isShutdown()) {
                return;
            }
            executorService.submit(() -> {
                try {
                    process(jobConfig, shardingContexts, each, jobExecutionEvent);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private void process(final JobConfiguration jobConfig, final ShardingContexts shardingContexts, final int item,
            final JobExecutionEvent startEvent) {
        //???
        jobFacade.postJobExecutionEvent(startEvent);
        log.trace("Job '{}' executing, item is: '{}'.", jobConfig.getJobName(), item);
        JobExecutionEvent completeEvent;
        try {
            //通过任务执行器执行elasticJob
            jobItemExecutor.process(elasticJob, jobConfig, jobFacade, shardingContexts.createShardingContext(item));
            completeEvent = startEvent.executionSuccess();
            log.trace("Job '{}' executed, item is: '{}'.", jobConfig.getJobName(), item);
            jobFacade.postJobExecutionEvent(completeEvent);
            // CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            // CHECKSTYLE:ON
            completeEvent = startEvent.executionFailure(ExceptionUtils.transform(cause));
            jobFacade.postJobExecutionEvent(completeEvent);
            //记录失败信息
            itemErrorMessages.put(item, ExceptionUtils.transform(cause));
            JobErrorHandler jobErrorHandler = executorContext.get(JobErrorHandler.class);
            jobErrorHandler.handleException(jobConfig.getJobName(), cause);
        }
    }

    /**
     * Shutdown executor.
     */
    public void shutdown() {
        executorContext.shutdown();
    }

}
