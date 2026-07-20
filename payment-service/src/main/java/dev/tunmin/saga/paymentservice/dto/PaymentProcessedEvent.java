package dev.tunmin.saga.paymentservice.dto;

import dev.tunmin.saga.paymentservice.domain.PaymentStatus;

public record PaymentProcessedEvent(
		String orderId,
		String customerId,
		String itemCode,
		int quantity,
	    double amount,
	    PaymentStatus status
) {}
