package dev.tunmin.saga.orderservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import dev.tunmin.saga.orderservice.domain.Order;
import dev.tunmin.saga.orderservice.domain.OrderStatus;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
	boolean existsByIdAndStatus(String id, OrderStatus status);
}
