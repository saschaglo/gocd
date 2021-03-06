/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.server.service;

import java.text.ParseException;
import java.util.List;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.CruiseConfig;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.TimerConfig;
import com.thoughtworks.go.listener.ConfigChangedListener;
import com.thoughtworks.go.server.scheduling.BuildCauseProducerService;
import com.thoughtworks.go.server.service.result.ServerHealthStateOperationResult;
import com.thoughtworks.go.serverhealth.HealthStateScope;
import com.thoughtworks.go.serverhealth.HealthStateType;
import com.thoughtworks.go.serverhealth.ServerHealthService;
import com.thoughtworks.go.serverhealth.ServerHealthState;
import org.apache.log4j.Logger;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.Trigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @understands scheduling pipelines based on a timer
 */
@Component
public class TimerScheduler implements ConfigChangedListener {
    private static final Logger LOG = Logger.getLogger(TimerScheduler.class);

    private GoConfigService goConfigService;
    private BuildCauseProducerService buildCauseProducerService;
    private SchedulerFactory quartzSchedulerFactory;
    private ServerHealthService serverHealthService;
    private static final String QUARTZ_GROUP = "CruiseTimers";
    private Scheduler quartzScheduler;

    @Autowired
    public TimerScheduler(SchedulerFactory quartzSchedulerFactory, GoConfigService goConfigService,
                          BuildCauseProducerService buildCauseProducerService, ServerHealthService serverHealthService) {
        this.goConfigService = goConfigService;
        this.buildCauseProducerService = buildCauseProducerService;
        this.quartzSchedulerFactory = quartzSchedulerFactory;
        this.serverHealthService = serverHealthService;
    }

    public void initialize() {
        try {
            quartzScheduler = quartzSchedulerFactory.getScheduler();
            quartzScheduler.start();
            scheduleAllJobs(goConfigService.getAllPipelineConfigs());
            goConfigService.register(this);
        } catch (SchedulerException e) {
            showGlobalError("Failed to initialize timer", e);
        }
    }

    private void scheduleAllJobs(List<PipelineConfig> pipelineConfigs) {
        for (PipelineConfig pipelineConfig : pipelineConfigs) {
            scheduleJob(quartzScheduler, pipelineConfig);
        }
    }

    private void scheduleJob(Scheduler scheduler, PipelineConfig pipelineConfig) {
        TimerConfig timer = pipelineConfig.getTimer();
        if (timer != null) {
            try {
                Trigger trigger = new CronTrigger(CaseInsensitiveString.str(pipelineConfig.name()), QUARTZ_GROUP, timer.getTimerSpec());

                JobDetail jobDetail = new JobDetail(CaseInsensitiveString.str(pipelineConfig.name()), QUARTZ_GROUP, SchedulePipelineQuartzJob.class);
                jobDetail.setJobDataMap(jobDataMapFor(pipelineConfig));

                scheduler.scheduleJob(jobDetail, trigger);
                LOG.info("Initialized timer for pipeline " + pipelineConfig.name() + " with " + timer.getTimerSpec());
            } catch (ParseException e) {
                showPipelineError(pipelineConfig, e,
                        "Bad timer specification for timer in Pipeline: " + pipelineConfig.name(),
                        "Cannot schedule pipeline using the timer");
            } catch (SchedulerException e) {
                showPipelineError(pipelineConfig, e,
                        "Could not register pipeline '" + pipelineConfig.name() + "' with timer",
                        "");
            }
        }
    }

    private void showGlobalError(String msg, SchedulerException e) {
        LOG.error(msg, e);
        serverHealthService.update(
                ServerHealthState.error(msg, "Cannot schedule pipelines using the timer",
                        HealthStateType.general(HealthStateScope.GLOBAL)));
    }

    private void showPipelineError(PipelineConfig pipelineConfig, Exception e, String msg, String description) {
        LOG.error(msg, e);
        serverHealthService.update(
                ServerHealthState.error(msg,
                        description,
                        HealthStateType.general(HealthStateScope.forPipeline(CaseInsensitiveString.str(pipelineConfig.name())))));
    }

    private JobDataMap jobDataMapFor(PipelineConfig pipelineConfig) {
        JobDataMap map = new JobDataMap();
        map.put("BuildCauseProducerService", buildCauseProducerService);
        map.put("PipelineConfig", pipelineConfig);
        return map;
    }

    public void onConfigChange(CruiseConfig newCruiseConfig) {
        unscheduleAllJobs();
        scheduleAllJobs(newCruiseConfig.getAllPipelineConfigs());
    }

    private void unscheduleAllJobs() {
        try {
            String[] jobNames = quartzScheduler.getJobNames(QUARTZ_GROUP);
            for (String jobName : jobNames) {
                quartzScheduler.unscheduleJob(jobName, QUARTZ_GROUP);
                quartzScheduler.deleteJob(jobName, QUARTZ_GROUP);
            }
        } catch (SchedulerException e) {
            LOG.error("Could not unschedule quartz jobs", e);
        }
    }

    public static class SchedulePipelineQuartzJob implements Job {
        public void execute(JobExecutionContext context) throws JobExecutionException {
            JobDataMap jobDataMap = context.getJobDetail().getJobDataMap();
            BuildCauseProducerService buildCauseProducerService = (BuildCauseProducerService) jobDataMap.get("BuildCauseProducerService");
            PipelineConfig pipelineConfig = (PipelineConfig) jobDataMap.get("PipelineConfig");
            buildCauseProducerService.timerSchedulePipeline(pipelineConfig, new ServerHealthStateOperationResult());
        }
    }
}
