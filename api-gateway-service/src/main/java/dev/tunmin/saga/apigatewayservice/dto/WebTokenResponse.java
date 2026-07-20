package dev.tunmin.saga.apigatewayservice.dto;

public record WebTokenResponse(
		String token, 
		String type, 
		long expiresIn
) {}
