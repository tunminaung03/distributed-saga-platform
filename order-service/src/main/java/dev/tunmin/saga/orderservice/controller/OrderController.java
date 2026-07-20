package dev.tunmin.saga.orderservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.tunmin.saga.orderservice.domain.Order;
import dev.tunmin.saga.orderservice.dto.CreateOrderRequest;
import dev.tunmin.saga.orderservice.exception.ClientIdentityMismatchException;
import dev.tunmin.saga.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST Edge Endpoint Controller for the Order Microservice.
 *
 * <p>This controller orchestrates incoming order placement requests under a decentralized,
 * high-concurrency microservice design pattern optimized for Java Virtual Threads. 
 *
 * <p><b>Perimeter Security & Ingress Principles:</b>
 * <ul>
 *   <li><b>Trust and Validation:</b> The controller relies on the API Gateway to perform the primary 
 *       cryptographic signature check in memory. The gateway then injects the trusted database primary key 
 *       into the {@code X-Perimeter-User-Id} header.</li>
 *   <li><b>Header Enforcement:</b> The {@code @RequestHeader(required = true)} constraint acts as an inner 
 *       ingress guard, automatically rejecting unauthorized requests that attempt to bypass the gateway perimeter.</li>
 *   <li><b>Identity Cross-Verification Check:</b> To prevent privilege escalation attacks (e.g., a malicious 
 *       actor trying to place an order on behalf of another user's account), the controller validates that the 
 *       injected header ID matches the payload's {@code customerId}. If they do not match, it throws a 
 *       {@code ClientIdentityMismatchException}, which is mapped to an HTTP 403 Forbidden response.</li>
 * </ul>
 *
 * <p><b>Context Propagation Blueprint:</b>
 * Once the perimeter security checks pass, the cryptographically verified user identity is forwarded to the 
 * service layer, saved to the database, and embedded within the transactional {@code OutboxRecord} payload. 
 * This ensures the user identity context flows asynchronously down to Kafka and downstream services 
 * with zero synchronous runtime overhead.
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {		
	
	private final OrderService orderService;	
	
	/** 
	 * HTTP POST /orders
	 * 
	 * 1. Validates incoming request data using @Valid and validation annotations in CreateOrderRequest.
	 * 2. Delegates to OrderService to handle business logic, including gRPC user validation and database transaction.
	 * 3. Returns the created Order object in the response body with HTTP 200 OK status.
	 */
    @PostMapping
    public ResponseEntity<?> createOrder(
    		@RequestHeader("X-Perimeter-User-Id") String headerCustomerId,
    		@Valid @RequestBody CreateOrderRequest request) {
        
    	log.info("Received HTTP create order for the user: {}, request: {}", 
    				headerCustomerId, request);
    	
    	if (!headerCustomerId.equals(request.customerId())) {
            throw new ClientIdentityMismatchException(
            			"Authenticated user ID does not match request customer ID payload.");
        }
        
        Order order = orderService.createOrder(headerCustomerId, request);
        return ResponseEntity.ok(order);                    
    }        
}
