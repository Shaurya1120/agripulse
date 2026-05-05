package com.agripulse.app.repository;

import com.agripulse.app.model.RiskReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

// @Repository marks this interface as a data-access component.
// Spring Data JPA will automatically create the implementation at runtime.
@Repository
public interface RiskReportRepository extends JpaRepository<RiskReport, Long> {

    Page<RiskReport> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
