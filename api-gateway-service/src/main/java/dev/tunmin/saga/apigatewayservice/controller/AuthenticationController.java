package dev.tunmin.saga.apigatewayservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.tunmin.saga.apigatewayservice.dto.AuthRequest;
import dev.tunmin.saga.apigatewayservice.dto.WebTokenResponse;
import dev.tunmin.saga.apigatewayservice.infra.grpc.UserGrpcClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthenticationController {

	private final UserGrpcClient userGrpcClient;

	@PostMapping("/login")
    public Mono<ResponseEntity<WebTokenResponse>> login(
    			@Valid @RequestBody AuthRequest request) {
        
		// With virtual threads enabled globally, can invoke the call cleanly.
        // WebFlux automatically handles execution safety without manual scheduler overrides.
        return Mono.fromCallable(() -> userGrpcClient.authenticate(request))
        		.map(grpcResponse -> 
        		
        			ResponseEntity.ok(
        					new WebTokenResponse(
        							grpcResponse.getToken(),
        							grpcResponse.getTokenType(),
        							grpcResponse.getExpiresIn()
        					)
        			)
        			
        		)
                .defaultIfEmpty(ResponseEntity.status(401).build());
    }
}
