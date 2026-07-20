package dev.tunmin.saga.paymentservice.dto;

public record RestaurantRejectedEvent(
		String orderId,
		String customerId,
		String reason

) {}
