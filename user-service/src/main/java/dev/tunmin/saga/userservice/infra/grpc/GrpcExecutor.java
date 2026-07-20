package dev.tunmin.saga.userservice.infra.grpc;

import com.google.common.base.Supplier;

import dev.tunmin.saga.userservice.exception.InvalidCredentialsException;
import dev.tunmin.saga.userservice.exception.UserInactiveException;
import dev.tunmin.saga.userservice.exception.UserNotFoundException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GrpcExecutor {

	/**
     * Executes a business action, handles success mapping, 
     * and guarantees safe gRPC error stream mapping.
     */
    public static <T> void execute(StreamObserver<T> responseObserver, Supplier<T> action) {
        try {
            // 1. Run business/mapping logic
            T response = action.get();
            
            // 2. Emit golden path output
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (UserNotFoundException ex) {
        	
            log.warn("User database record missing: {}", ex.getMessage());
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(ex.getMessage())
                    .asRuntimeException());
                    
        } catch (InvalidCredentialsException ex) {
        	
            log.warn("Authentication failed due to invalid credentials: {}", ex.getMessage());
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription(ex.getMessage())
                    .asRuntimeException());
                    
        } catch (UserInactiveException ex) {
        	
            log.warn("Access denied for deactivated user account: {}", ex.getMessage());
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription(ex.getMessage())
                    .asRuntimeException());
                    
        } catch (IllegalArgumentException ex) {
        	
            log.warn("gRPC client sent invalid payload data: {}", ex.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(ex.getMessage())
                    .asRuntimeException());
                    
        } catch (Exception ex) {
        	
            log.error("Unhandled runtime or infrastructure exception occurred: ", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("An unexpected internal service error occurred.")
                    .asRuntimeException());
        }
    }

}
