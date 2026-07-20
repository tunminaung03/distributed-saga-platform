package dev.tunmin.saga.apigatewayservice.exception;

@SuppressWarnings("serial")
public class UserNotFoundException extends RuntimeException {
	
	public UserNotFoundException(String message) { 
		super(message); 
	}
}
