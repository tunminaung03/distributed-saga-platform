package dev.tunmin.saga.apigatewayservice.infra.grpc;

import org.springframework.stereotype.Component;

import dev.tunmin.saga.apigatewayservice.exception.DependencyUnavailableException;
import dev.tunmin.saga.apigatewayservice.exception.InvalidCredentialsException;
import dev.tunmin.saga.apigatewayservice.exception.UserInactiveException;
import dev.tunmin.saga.apigatewayservice.exception.UserNotFoundException;
import io.grpc.StatusRuntimeException;

@Component
public class UserGrpcExceptionTranslator {
	
	public RuntimeException translate(StatusRuntimeException ex) {
		
        return switch (ex.getStatus().getCode()) {

            case UNAUTHENTICATED ->
                new InvalidCredentialsException("Invalid email or password provided");

            case NOT_FOUND ->
                new UserNotFoundException("User account does not exist");
            
            case INVALID_ARGUMENT ->
                new IllegalArgumentException("Invalid user request fields");
                
            case PERMISSION_DENIED ->
                new UserInactiveException("User account is currently deactivated");
            
            case UNAVAILABLE, DEADLINE_EXCEEDED, INTERNAL ->
                new DependencyUnavailableException("User authentication service is temporarily unavailable", ex);
            
            default ->
                new DependencyUnavailableException("Unexpected internal gRPC system error occurred", ex);
        };
    }
}
