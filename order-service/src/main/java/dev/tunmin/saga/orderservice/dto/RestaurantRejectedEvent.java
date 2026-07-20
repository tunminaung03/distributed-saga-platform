package dev.tunmin.saga.orderservice.dto;

public record RestaurantRejectedEvent(
		
		String orderId,
		String customerId,
		String reason

) {}
