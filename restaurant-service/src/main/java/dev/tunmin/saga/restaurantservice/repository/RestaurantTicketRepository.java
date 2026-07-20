package dev.tunmin.saga.restaurantservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import dev.tunmin.saga.restaurantservice.domain.RestaurantTicket;

@Repository
public interface RestaurantTicketRepository extends JpaRepository<RestaurantTicket, String> {
	boolean existsByOrderId(String orderId);
}
