package dev.tunmin.saga.orderservice.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import dev.tunmin.saga.orderservice.domain.Order;
import dev.tunmin.saga.orderservice.domain.OrderStatus;
import dev.tunmin.saga.orderservice.domain.OutboxRecord;
import dev.tunmin.saga.orderservice.dto.CreateOrderRequest;
import dev.tunmin.saga.orderservice.dto.OrderCreatedEvent;
import dev.tunmin.saga.orderservice.dto.RestaurantApprovedEvent;
import dev.tunmin.saga.orderservice.dto.RestaurantRejectedEvent;
import dev.tunmin.saga.orderservice.repository.OrderRepository;
import dev.tunmin.saga.orderservice.repository.OutboxRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/** 
 * OrderService is responsible for handling all order-related operations in the Saga workflow.
 * It processes incoming CreateOrderRequests to create new orders and handles restaurant approval/rejection events.
 * The service ensures idempotency, robust error handling, and maintains data consistency across distributed transactions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    
	private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";
	private static final String INVALID_SPAN_ID = "0000000000000000";
	
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    private final OutboxRepository outboxRepository;    
    
    /**
	 * Creates a new order in the system and initiates the SAGA orchestration by saving an outbox event.
	 * This method is transactional to ensure atomicity of order creation and outbox event persistence.
	 *
	 * @param authenticatedUserId The ID of the authenticated user creating the order.
	 * @param request The request object containing order details such as item code, quantity, and total amount.
	 * 
	 * @return The saved Order entity with its generated ID and status.
	 */
    @Transactional
    public Order createOrder(String authenticatedUserId, CreateOrderRequest request) {
        log.info("[START] Processing business logic for create order: {}", request);     
        
        // Core Database Transaction Processing
        Order savedOrder = saveOrder(authenticatedUserId, request);

        // Async SAGA Orchestration Output
        saveOrderOutbox(savedOrder);
        
        log.info("[END] Processing business logic for create order: {}", request);
        return savedOrder;
    }
    
    /** 
     * Handles the restaurant approval event to confirm the order in the system.
	 * This method is transactional to ensure that the order status update and any related operations are atomic.
	 *
	 * @param event The RestaurantApprovedEvent containing order details and approval status.
	 */
    @Transactional
    public void confirmOrder(RestaurantApprovedEvent event) {
		
    	log.info("Received restaurant approval for Order ID: {}. Marking as CONFIRMED.", 
					event.orderId());
		
    	// PROACTIVE IDEMPOTENCY GUARD: Check for duplicate execution
    	boolean existingOrder = orderRepository.existsByIdAndStatus(
    												event.orderId(), OrderStatus.SUCCESS);
    	if (existingOrder) {
            log.debug("Idempotency Guard: Order already exists with ID: {} and status:SUCCESS.",  
                    event.orderId());
    		return;
    	}
    	    	
    	Order order = findOrder(event.orderId());
    	 if (order.getStatus() == OrderStatus.SUCCESS) {
    		 
             log.debug("Order {} already SUCCESS after load; skipping.", 
            		 	event.orderId());
             return;
         }
		
		order.setStatus(OrderStatus.SUCCESS);
		orderRepository.save(order);
		
		log.info("Transaction Complete! Order {} is now marked as SUCCESS.", 
    			event.orderId());
    }
    
    /** 
	 * Handles the restaurant rejection event to cancel the order in the system.
	 * This method is transactional to ensure that the order status update and any related operations are atomic.
	 * 
	 * @param event The RestaurantRejectedEvent containing order details and rejection reason. 
	 */
    @Transactional
    public void cancelOrder(RestaurantRejectedEvent event) {
		
		log.warn("Received restaurant rejection for Order ID: {}. Marking as CANCELLED.", 
					event.orderId());
		
    	// PROACTIVE IDEMPOTENCY GUARD: Check for duplicate execution
    	boolean existingOrder = orderRepository.existsByIdAndStatus(
    												event.orderId(), OrderStatus.CANCELLED);
    	if (existingOrder) {
    		
            log.debug("Idempotency Guard: Order already exists with ID: {} and status:SUCCESS.",  
                    	event.orderId());
    		return;
    	}

		
		Order order = findOrder(event.orderId());
		if (order.getStatus() == OrderStatus.CANCELLED) {
			
            log.debug("Order {} already CANCELLED after load; skipping.", 
            			event.orderId());
            return;
        }
		
		order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        
        log.warn("Order {} cancelled due to kitchen stock rejection.", 
        			event.orderId());
    }
    
    /// -- PRIVATE HELPER METHODS -- ///
    
    /// Private helper method to find an order by its ID. Throws an exception if the order is not found.
    private Order findOrder(String orderId) {
		return orderRepository.findById(orderId)
					.orElseThrow(
							() -> new IllegalArgumentException("Order ID " + orderId + "not found in database."));
	}
    
    /// Encapsulates the order creation and persistence logic to the database
    /// This method is transactional to ensure that the order is saved atomically.
    private Order saveOrder(String authenticatedUserId, CreateOrderRequest request) {
    	
    	String orderId = UUID.randomUUID().toString();
        Order order = Order.builder()
                .id(orderId)
                .customerId(authenticatedUserId)
                .totalAmount(request.totalAmount())
                .status(OrderStatus.PENDING)
                .itemCode(request.itemCode())
                .quantity(request.quantity())
                .build();
        
        Order savedOrder = orderRepository.save(order);
        log.info("Order {} saved to database with status: {}", 
        			orderId, order.getStatus());
        
		return savedOrder;
	}
    
    /// Saves an outbox record for the created order to facilitate asynchronous SAGA orchestration.
    /// This method captures the current OpenTelemetry context and includes traceId and spanId in the outbox record for distributed tracing.
    private void saveOrderOutbox(Order order) {
    	
        OrderCreatedEvent event = new OrderCreatedEvent(
                order.getId(), order.getCustomerId(), 
                order.getItemCode(), order.getQuantity(), 
                order.getTotalAmount(), order.getStatus()
        );
        
        String jsonPayload;
        try {
            
            jsonPayload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Transactional Outbox failed to serialize event for order: " 
            							+ order.getId(), e);
        }
            
        // Capture active OpenTelemetry Context Strings ---
        SpanContext activeContext = Span.current().getSpanContext();
        // Fallbacks protect local unit tests if executed outside an active telemetry container
        String traceId = activeContext.isValid() ? activeContext.getTraceId() 
        					: INVALID_TRACE_ID;
        String spanId = activeContext.isValid() ? activeContext.getSpanId() 
        					: INVALID_SPAN_ID;
        
        OutboxRecord outbox = OutboxRecord.builder()
        		.aggregateId(order.getId())            // Bound to Kafka partition keys later
        		.eventType("OrderCreated")
        		.payload(jsonPayload)
        		.createdTime(LocalDateTime.now())
        		.traceId(traceId)
        		.spanId(spanId)
        		.build();
        
        outboxRepository.save(outbox);
        log.info("Saved outbox for order with payload:{}", outbox.getPayload());
    }
}
