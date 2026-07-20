package dev.tunmin.saga.apigatewayservice.infra.grpc;

import org.springframework.stereotype.Component;

import dev.tunmin.saga.user.grpc.AuthRequest;
import dev.tunmin.saga.user.grpc.AuthResponse;
import dev.tunmin.saga.user.grpc.UserServiceGrpc.UserServiceBlockingStub;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserGrpcClient {
	
    private final UserServiceBlockingStub userServiceStub;
    private final UserGrpcExceptionTranslator translator;
    
    public AuthResponse authenticate(dev.tunmin.saga.apigatewayservice.dto.AuthRequest webAuthRequest) {
    	
    	log.info("Initiating gRPC call to authenticate user with username: {}", 
    				webAuthRequest.username());
    	
    	AuthResponse response = null;
    	try {
    		AuthRequest grpcRequest = AuthRequest.newBuilder()
        			.setEmail(webAuthRequest.username())
        			.setPassword(webAuthRequest.password())
        			.build();
        	
        	response = userServiceStub.authenticateUser(grpcRequest);        	
        	
        	log.info("Authenticated user successfully with username: {}", 
    					webAuthRequest.username());
        	
		} catch (StatusRuntimeException e) {
			log.error("gRPC invocation returned an error status: [{}] - Reason: {}", 
		            e.getStatus().getCode(), e.getStatus().getDescription());
			throw translator.translate(e);
		}
    	return response;
    }
}
