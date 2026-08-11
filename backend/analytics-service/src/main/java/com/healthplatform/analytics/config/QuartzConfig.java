package com.healthplatform.analytics.config;

import com.healthplatform.analytics.scheduler.DailyReportJob;
import org.quartz.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SimpleTriggerFactoryBean;

@Configuration
public class QuartzConfig {

    @Bean
    public JobDetailFactoryBean dailyReportJobDetail() {
        JobDetailFactoryBean factory = new JobDetailFactoryBean();
        factory.setJobClass(DailyReportJob.class);
        factory.setDurability(true); // job survives even with no trigger currently attached
        factory.setName("dailyReportJob");
        return factory;
    }

    @Bean
    public SimpleTriggerFactoryBean dailyReportTrigger(JobDetail dailyReportJobDetail) {
        SimpleTriggerFactoryBean trigger = new SimpleTriggerFactoryBean();
        trigger.setJobDetail(dailyReportJobDetail);
        trigger.setRepeatInterval(24L * 60 * 60 * 1000); // once every 24h
        trigger.setStartDelay(60_000L); // give the app a minute to fully start
        return trigger;
    }

    @Bean
    public AutowiringSpringBeanJobFactory springBeanJobFactory() {
        return new AutowiringSpringBeanJobFactory();
    }

    @Bean
    public org.springframework.scheduling.quartz.SchedulerFactoryBean schedulerFactoryBean(
            AutowiringSpringBeanJobFactory springBeanJobFactory) {
        var factory = new org.springframework.scheduling.quartz.SchedulerFactoryBean();
        factory.setJobFactory(springBeanJobFactory);
        return factory;
    }
}
