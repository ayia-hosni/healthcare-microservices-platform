package com.healthplatform.analytics.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthplatform.analytics.domain.DailyReport;
import com.healthplatform.analytics.domain.EventCounter;
import com.healthplatform.analytics.repository.DailyReportRepository;
import com.healthplatform.analytics.repository.EventCounterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the Quartz job's business logic. DailyReportJob is instantiated by Quartz via
 * a no-arg constructor and field-injected (see AutowiringSpringBeanJobFactory), so dependencies
 * are wired here via ReflectionTestUtils rather than a constructor.
 */
class DailyReportJobTest {

    private EventCounterRepository eventCounterRepository;
    private DailyReportRepository dailyReportRepository;
    private DailyReportJob job;

    @BeforeEach
    void setUp() {
        eventCounterRepository = mock(EventCounterRepository.class);
        dailyReportRepository = mock(DailyReportRepository.class);
        job = new DailyReportJob();
        ReflectionTestUtils.setField(job, "eventCounterRepository", eventCounterRepository);
        ReflectionTestUtils.setField(job, "dailyReportRepository", dailyReportRepository);
        ReflectionTestUtils.setField(job, "objectMapper", new ObjectMapper());
    }

    @Test
    void summarizesYesterdaysCountersIntoAJsonReport() throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        EventCounter apptCounter = new EventCounter("APPOINTMENT_CREATED", yesterday);
        apptCounter.increment();
        apptCounter.increment();
        EventCounter patientCounter = new EventCounter("PATIENT_REGISTERED", yesterday);
        patientCounter.increment();
        when(eventCounterRepository.findAllByCounterDate(yesterday)).thenReturn(List.of(apptCounter, patientCounter));

        JobExecutionContext context = mock(JobExecutionContext.class);
        job.execute(context);

        var captor = org.mockito.ArgumentCaptor.forClass(DailyReport.class);
        verify(dailyReportRepository).save(captor.capture());
        DailyReport saved = captor.getValue();
        assertThat(saved.getReportDate()).isEqualTo(yesterday);
        assertThat(saved.getSummaryJson())
                .contains("\"APPOINTMENT_CREATED\":2")
                .contains("\"PATIENT_REGISTERED\":1");
    }

    @Test
    void savesEmptySummaryWhenNoEventsOccurredYesterday() throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(eventCounterRepository.findAllByCounterDate(yesterday)).thenReturn(List.of());

        job.execute(mock(JobExecutionContext.class));

        var captor = org.mockito.ArgumentCaptor.forClass(DailyReport.class);
        verify(dailyReportRepository).save(captor.capture());
        assertThat(captor.getValue().getSummaryJson()).isEqualTo("{}");
    }
}
