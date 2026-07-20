package dev.tunmin.saga.userservice.exception;

@SuppressWarnings("serial")
public class UserInactiveException extends RuntimeException {
	
	public UserInactiveException(String message) {
		super(message);
	}
	
}
