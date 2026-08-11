package com.healthplatform.analytics.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthplatform.analytics.domain.DailyReport;
import com.healthplatform.analytics.repository.DailyReportRepository;
import com.healthplatform.analytics.repository.EventCounterRepository;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Uses QUARTZ rather than a plain @Scheduled method — unlike identity-service's token
 * cleanup (a stateless, single-instance-safe task), this job's execution history needs to
 * survive a restart (so it isn't silently skipped or double-run across a multi-instance
 * deployment) and its trigger schedule may need to become dynamic later (e.g. an admin
 * changing report time per department). Quartz's JobStore persists that state in Postgres;
 * @Scheduled has no equivalent without hand-rolling a distributed lock.
 *
 * NOT a @Component: Quartz instantiates Job classes itself via a no-arg constructor, so
 * dependencies are injected afterward via AutowiringSpringBeanJobFactory (see QuartzConfig)
 * rather than through the constructor.
 */
public class DailyReportJob implements Job {

    @Autowired
    private EventCounterRepository eventCounterRepository;
    @Autowired
    private DailyReportRepository dailyReportRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void execute(JobExecutionContext context) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Map<String, Long> summary = eventCounterRepository.findAllByCounterDate(yesterday).stream()
                .collect(Collectors.toMap(ec -> ec.getEventType(), ec -> ec.getCount()));

        try {
            String json = objectMapper.writeValueAsString(summary);
            dailyReportRepository.save(new DailyReport(yesterday, json));
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate daily report for " + yesterday, e);
        }
    }
}
