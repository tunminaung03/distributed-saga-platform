package dev.tunmin.saga.orderservice.exception;

@SuppressWarnings("serial")
public class ClientIdentityMismatchException extends RuntimeException {
	
	public ClientIdentityMismatchException(String message) {
		super(message);
	}

}
