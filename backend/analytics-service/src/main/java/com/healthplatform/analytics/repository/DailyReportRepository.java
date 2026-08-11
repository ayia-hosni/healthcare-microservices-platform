package com.healthplatform.analytics.repository;

import com.healthplatform.analytics.domain.DailyReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DailyReportRepository extends JpaRepository<DailyReport, UUID> {
}
