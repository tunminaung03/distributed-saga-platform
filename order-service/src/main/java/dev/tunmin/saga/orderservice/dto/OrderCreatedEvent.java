package dev.tunmin.saga.orderservice.dto;

import dev.tunmin.saga.orderservice.domain.OrderStatus;

public record OrderCreatedEvent(
	String orderId,
	String customerId,
	String itemCode,
	int quantity,
	double totalAmount,
	OrderStatus status
) {}
