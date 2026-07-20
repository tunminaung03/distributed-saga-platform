package dev.tunmin.saga.restaurantservice.dto;

public sealed interface RestaurantEvent 
	permits RestaurantApprovedEvent, 
			RestaurantRejectedEvent {
    
	// Declaring common fields as abstract methods
    String orderId();
    String customerId();

}
