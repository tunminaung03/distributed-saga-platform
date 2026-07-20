package dev.tunmin.saga.apigatewayservice.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.tunmin.saga.apigatewayservice.exception.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/fallback")
@Slf4j
public class GatewayFallbackController {

    /**
     * Graceful fallback catcher when downstream order operations fail or time out.
     */
    @RequestMapping("/orders")
    public Mono<ResponseEntity<ErrorResponse>> orderFallback() {
    	
        log.warn("Resilience4j Edge Alert: Order microservice path is unreachable or circuit is OPEN. Routing to fallback.");
        
        ErrorResponse errorBody = new ErrorResponse(
                "SERVICE_UNAVAILABLE",
                "The order placement infrastructure is temporarily overloaded. Please try again in a few moments.",
                System.currentTimeMillis()
        );
        
        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorBody));
    }
}
