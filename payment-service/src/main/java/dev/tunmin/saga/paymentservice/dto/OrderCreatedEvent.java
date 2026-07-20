package dev.tunmin.saga.paymentservice.dto;

public record OrderCreatedEvent(
		String orderId, 
	    String customerId,
		String itemCode,
		int quantity,
	    double totalAmount, 
	    OrderStatus status
) {}
