package dev.tunmin.saga.apigatewayservice.exception;


public record ErrorResponse(
		String error,
	    String message,
	    long timestamp
		
) {}

