package dev.tunmin.saga.paymentservice.dto;

public record RestaurantPreparedEvent(
		
	String orderId,
	String restaurantId,
	RestaurantTicketStatus status		
) {}
