package dev.tunmin.saga.paymentservice.dto;

// Currently no implementation for FAILED case, but can integrate with gateway later
public record PaymentFailedEvent(
		String orderId,
		String customerId,
		String reason
) {}
