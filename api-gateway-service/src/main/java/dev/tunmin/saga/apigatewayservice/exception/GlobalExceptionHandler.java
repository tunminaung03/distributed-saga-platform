package dev.tunmin.saga.apigatewayservice.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
	
	@ExceptionHandler(WebExchangeBindException.class)
	public ResponseEntity<Map<String, String>> handleReactiveValidationExceptions(WebExchangeBindException ex) {
	    Map<String, String> errors = new HashMap<>();
	    
	    // Extract validation errors out of the WebFlux binding context result
	    ex.getBindingResult()
	      	.getFieldErrors().forEach(error -> 
	      		errors.put(error.getField(), error.getDefaultMessage())
	      	);
	    
	    log.warn("Gateway edge validation payload constraints violated: {}", errors);

	    // Returning an explicit ResponseEntity forces WebFlux TestClient to capture the 400 status
	    return ResponseEntity.badRequest().body(errors);
	}		
		
	@ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(UserNotFoundException ex) {
		
		log.warn("User not found: {}", ex.getMessage());
        return buildResponse(
        			HttpStatus.NOT_FOUND, 
        			"NOT_FOUND", 
        			ex.getMessage(),
        			ex
        		);
    }
	
	@ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(InvalidCredentialsException ex) {
		
		log.warn("Invalid Credential: {}", ex.getMessage());
        return buildResponse(
        			HttpStatus.UNAUTHORIZED, 
        			"UNAUTHORIZED", 
        			ex.getMessage(),
        			ex
        		);
    }
	
    @ExceptionHandler(UserInactiveException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(UserInactiveException ex) {
    	
    	log.warn("User is inactive: {}", ex.getMessage());
        return buildResponse(
        			HttpStatus.FORBIDDEN, 
        			"FORBIDDEN", 
        			ex.getMessage(),
        			ex
        		);
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleDependencyFailure(
            DependencyUnavailableException ex) {

        log.error("Downstream service failure", ex);
        return buildResponse(
        			HttpStatus.SERVICE_UNAVAILABLE, 
        			"DEPENDENCY_FAILURE", 
        			"Service temporarily unavailable",
        			ex
        		);
    }
    
    /// [START] JWT related exception handler
    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<ErrorResponse> handleTokenExpired(TokenExpiredException ex) {
    	
        // Standardized log trace mapping matching your style
        log.warn("Perimeter token has expired: {}", ex.getMessage());
        
        return buildResponse(
	                HttpStatus.FORBIDDEN, 
	                "FORBIDDEN", 
	                "Security Perimeter Verification Failed: " + ex.getMessage(),
	                ex
        		);
    }

    @ExceptionHandler(JWTVerificationException.class)
    public ResponseEntity<ErrorResponse> handleJwtFailure(JWTVerificationException ex) {
    	
        log.error("Perimeter token verification failed: {}", ex.getMessage());
        
        return buildResponse(
	                HttpStatus.UNAUTHORIZED, 
	                "UNAUTHORIZED", 
	                "Security Perimeter Verification Failed: Token is invalid or malformed.",
	                ex
        		);
    }
    /// [END] JWT related exception handler
    
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
    	
    	log.warn("Invalid request: {}", ex.getMessage());
        return buildResponse(
        		HttpStatus.BAD_REQUEST, 
        		"BAD_REQUEST", 
        		ex.getMessage(),
        		ex);
    }
    
     @ExceptionHandler(Exception.class)
	 public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
		 
		 log.error("Unexpected error occurred", ex);
		 return buildResponse(
				 	HttpStatus.INTERNAL_SERVER_ERROR, 
				 	"INTERNAL_SERVER_ERROR", 
				 	"An unexpected error occurred",
				 	ex);		 
	 }
     
     private ResponseEntity<ErrorResponse> buildResponse(
								    		 	HttpStatus status, String error, 
								    		 	String message, Throwable ex) {
    	 
    	// 1. Synchronize the Active Otel Span with this exact error state
    	 enrichTraceSpanWithExeDetails(status, error, ex);
    	 
    	 ErrorResponse body = new ErrorResponse(
        		 error, message, 
        		 System.currentTimeMillis()
         );
         return ResponseEntity.status(status)
        		 				.body(body);
     }
     
     /**
      * Pure Telemetry Extractor Method: Segregates 4xx Client Validation from 5xx Server Crashes
      */
     private void enrichTraceSpanWithExeDetails(
		    		 HttpStatus status, String errorLabel, 
		    		 Throwable ex) {
    	 
         Span currentSpan = Span.current();
         if (!currentSpan.getSpanContext().isValid()) {
             return; // Protects local environment execution loops if OTel is disabled
         }

         // Apply standard semantic attributes
         currentSpan.setAttribute("http.status_code", status.value());
         currentSpan.setAttribute("error.type", ex.getClass().getName());
         currentSpan.setAttribute("error.message", ex.getMessage());

         // Enforce structural symmetry: Only 5xx errors represent true infrastructure crashes
         if (status.is5xxServerError()) {
        	 
             currentSpan.setStatus(StatusCode.ERROR, 
            		 				"Internal Infrastructure Failure: " + ex.getMessage());
             currentSpan.recordException(ex); // Fills exception block in Tempo ONLY for severe crashes
         } else {
        	 
             // 4xx Client Validation Boundaries remain StatusCode.UNSET/OK with attributes attached
             currentSpan.setStatus(StatusCode.UNSET); 
             currentSpan.setAttribute("security.perimeter.action", "REJECTED");
             currentSpan.setAttribute("security.perimeter.code", errorLabel);
         }
     }
     
     
}
