package dev.tunmin.saga.apigatewayservice.dto;

public record TokenResponse(
		String token,
	    String type,
	    long expiresIn
) {}
