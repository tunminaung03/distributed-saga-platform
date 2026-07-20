package dev.tunmin.saga.orderservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
		
		@NotBlank(message = "Customer ID is required")		
		String customerId,
		
		@Positive(message = "Total amount must be > 0")
		double totalAmount,
		
		@NotBlank(message = "Item code is required")
		String itemCode,
		
		@Positive(message = "Quantity must be > 0")
		int quantity
) {}
