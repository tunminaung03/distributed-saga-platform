package dev.tunmin.saga.restaurantservice.dto;

public record RestaurantApprovedEvent(
		
		String orderId,
		String customerId,
		String ticketId
) implements RestaurantEvent {}
