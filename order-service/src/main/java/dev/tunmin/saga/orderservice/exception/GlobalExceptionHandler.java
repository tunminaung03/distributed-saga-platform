package dev.tunmin.saga.orderservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

/** 
 * Global exception handler for the Order Service application.
 * Catches and processes exceptions thrown by controllers and services,
 * translating them into standardized HTTP responses with appropriate status codes and error messages.
 * 
 * This centralizes error handling logic, ensuring consistent responses across the API and improving maintainability.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {			
	
	@ExceptionHandler(ClientIdentityMismatchException.class)
	public ResponseEntity<ErrorResponse> handleIdentityMismatch(
			ClientIdentityMismatchException ex) {
		
	    log.warn("Perimeter identity validation failed: {}", ex.getMessage());
	    return buildResponse( HttpStatus.FORBIDDEN, 
	    						"IDENTITY_MISMATCH", 
	    						ex.getMessage()
	    );
	}
	
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {
	    // 1. Gather all constraint violation messages into a single clean string
	    StringBuilder errorMessage = new StringBuilder("Validation failed: ");
	    
	    ex.getBindingResult()
	      .getFieldErrors()
	      .forEach(error -> errorMessage.append("[")
	                                    .append(error.getField())
	                                    .append(": ")
	                                    .append(error.getDefaultMessage())
	                                    .append("] "));

	    log.warn("Payload constraints violated: {}", errorMessage);

	    // 2. Return your unified envelope with an HTTP 400 Bad Request status
	    return buildResponse(
	        HttpStatus.BAD_REQUEST, 
	        "VALIDATION_FAILURE", 
	        errorMessage.toString().trim()
	    );
	}
	
	@ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
    	
        log.warn("Security ingress warning: Missing mandatory perimeter tracking header. Parameter: {}", 
        			ex.getHeaderName());
        
        // Convert the exception cleanly to an informative HTTP 400 Bad Request structure
        return buildResponse(
        			HttpStatus.BAD_REQUEST, 
        			"MISSING_REQUIRED_HEADER", 
        			"The mandatory edge security perimeter identity context header is missing."
        );
    }
	
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
    	
    	log.warn("Invalid request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, 
        						"BAD_REQUEST", 
        						ex.getMessage());
    }
    
     @ExceptionHandler(Exception.class)
	 public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
		 
		 log.error("Unexpected error occurred", ex);
		 return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
				 				"INTERNAL_SERVER_ERROR", 
				 				"An unexpected error occurred");		 
	 }
     
     private ResponseEntity<ErrorResponse> buildResponse(
    		 	HttpStatus status, String error, String message) {
    	 
         ErrorResponse body = new ErrorResponse(
        		 					error, message, 
        		 					System.currentTimeMillis()
         );
         return ResponseEntity.status(status).body(body);
     }
}
