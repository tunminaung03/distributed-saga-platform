package dev.tunmin.saga.apigatewayservice.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class JwtPerimeterGuardGatewayFilterFactory 
		extends AbstractGatewayFilterFactory<JwtPerimeterGuardGatewayFilterFactory.Config>{
	
	private final JWTVerifier jwtVerifier;

    public JwtPerimeterGuardGatewayFilterFactory(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.issuer}") String issuer) {
        
    	super(Config.class);
        
        // OPTIMIZATION: Build the verifier once on startup to save memory and CPU cycles
        Algorithm algorithm = Algorithm.HMAC256(secret);
        this.jwtVerifier = JWT.require(algorithm)
                			.withIssuer(issuer)
                			.build();
        log.info("JwtPerimeterGuardGatewayFilterFactory initialized successfully with issuer: {}", 
        			issuer);
    }

    @Override
    public GatewayFilter apply(Config config) {
    	
        return (exchange, chain) -> {
        	
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getPath().value();

            // Combined existence check and retrieval into a single step
            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            	
            	log.warn("Missing or malformed Authorization header for request path: {}", path);
                
                // Routes straight to GatewayFilterExceptionHandler for a clean 401 JSON response
                return Mono.error( new JWTVerificationException(
                						"Missing or malformed perimeter authentication credentials.")
                		);
            }

            String token = authHeader.substring(7);

            try {
                // Execute in-memory cryptographic check
                DecodedJWT jwt = this.jwtVerifier.verify(token);
                
                // Extract the numeric user identity claim rather than the subject string
                String userId = jwt.getClaim("user-id").asString();
                
                log.debug("Token verified successfully. Path: {}, User ID: {}", 
                			path, userId);

                // Mutate request using the standardized downstream header tracking label
                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                        .header("X-Perimeter-User-Id", userId)
                        .build();

                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            } catch (Exception e) {
            	
            	log.error("JWT verification failed for request path: {}. Error: {}", 
            				path, e.getMessage());
                
                return Mono.error(e);                            	
            }
        };
    }

    public static class Config {}
}
