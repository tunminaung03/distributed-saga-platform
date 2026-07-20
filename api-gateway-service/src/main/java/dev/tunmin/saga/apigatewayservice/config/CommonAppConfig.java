package dev.tunmin.saga.apigatewayservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

import dev.tunmin.saga.user.grpc.UserServiceGrpc;
import dev.tunmin.saga.user.grpc.UserServiceGrpc.UserServiceBlockingStub;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class CommonAppConfig {		

    /**
     * Auto-wires the core UserService gRPC binary communication pipeline stub.
     * 
     * @param channelFactory Spring gRPC's factory resolver
     * @return A thread-safe blocking stub pointing to user-service via Istio Cluster DNS
     */
    @Bean
    UserServiceBlockingStub userServiceStub(GrpcChannelFactory channelFactory) {
    	
        log.info("Auto-configuring UserServiceBlockingStub mapping using named channel: [userService]");
        
        // Matches config in your gateway application.yaml under spring.grpc.client.channels.userService
        return UserServiceGrpc.newBlockingStub(
                channelFactory.createChannel("userService")
        );
    }
    
}
