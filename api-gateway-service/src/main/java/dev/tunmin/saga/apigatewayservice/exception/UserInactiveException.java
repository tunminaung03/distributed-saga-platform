package dev.tunmin.saga.apigatewayservice.exception;

@SuppressWarnings("serial")
public class UserInactiveException extends RuntimeException {
	
	public UserInactiveException(String message) { 
		super(message); 
	}
}
