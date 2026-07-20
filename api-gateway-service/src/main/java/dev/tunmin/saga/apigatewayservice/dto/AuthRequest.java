package dev.tunmin.saga.apigatewayservice.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthRequest(
	    @NotBlank 
	    String username,
	    @NotBlank 
	    String password
) {}
