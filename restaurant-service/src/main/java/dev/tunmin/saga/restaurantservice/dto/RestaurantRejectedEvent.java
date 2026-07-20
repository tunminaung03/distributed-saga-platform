package dev.tunmin.saga.restaurantservice.dto;

public record RestaurantRejectedEvent(
		String orderId,
		String customerId,
		String reason
) implements RestaurantEvent {

}
