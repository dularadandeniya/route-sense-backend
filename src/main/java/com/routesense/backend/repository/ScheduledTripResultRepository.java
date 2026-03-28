package com.routesense.backend.repository;

import com.routesense.backend.entity.ScheduledTripResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledTripResultRepository extends JpaRepository<ScheduledTripResult, Long> {
}