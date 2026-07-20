package dev.tunmin.saga.restaurantservice.dto;

public record PaymentProcessedEvent(
		String orderId,
		String customerId,
		String itemCode,
		int quantity,
	    double amount,
	    PaymentStatus status
) {}
