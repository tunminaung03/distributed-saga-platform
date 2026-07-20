package dev.tunmin.saga.orderservice.dto;

public record RestaurantApprovedEvent(
		
		String orderId,
		String customerId,
		String ticketId
		
) {}
