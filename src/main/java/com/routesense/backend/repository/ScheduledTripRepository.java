package com.routesense.backend.repository;

import com.routesense.backend.entity.ScheduledTrip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledTripRepository extends JpaRepository<ScheduledTrip, Long> {
}