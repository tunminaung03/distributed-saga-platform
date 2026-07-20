package dev.tunmin.saga.orderservice.exception;

public record ErrorResponse(
		String error,
	    String message,
	    long timestamp
		
) {}
