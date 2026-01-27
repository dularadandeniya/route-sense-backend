package com.routesense.backend.repository;

import com.routesense.backend.model.DeliveryOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<DeliveryOrder, Long> {
}
