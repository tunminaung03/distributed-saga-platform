package dev.tunmin.saga.restaurantservice.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import dev.tunmin.saga.restaurantservice.domain.InventoryStatus;
import dev.tunmin.saga.restaurantservice.domain.OutboxRecord;
import dev.tunmin.saga.restaurantservice.domain.RestaurantTicket;
import dev.tunmin.saga.restaurantservice.domain.RestaurantTicketStatus;
import dev.tunmin.saga.restaurantservice.dto.PaymentProcessedEvent;
import dev.tunmin.saga.restaurantservice.dto.PaymentStatus;
import dev.tunmin.saga.restaurantservice.dto.RestaurantApprovedEvent;
import dev.tunmin.saga.restaurantservice.dto.RestaurantEvent;
import dev.tunmin.saga.restaurantservice.dto.RestaurantRejectedEvent;
import dev.tunmin.saga.restaurantservice.repository.OutboxRepository;
import dev.tunmin.saga.restaurantservice.repository.RestaurantTicketRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * RestaurantService is responsible for handling all restaurant-related operations in the Saga workflow.
 * It processes incoming PaymentProcessedEvents to check inventory, create restaurant tickets, and generate outbox events.
 * The service ensures idempotency, robust validation of required fields and business rules, and maintains data consistency 
 * across distributed transactions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RestaurantService {
	
	private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";
	private static final String INVALID_SPAN_ID = "0000000000000000";
	
	private final RestaurantTicketRepository ticketRepository;
    private final RestaurantInventoryService inventoryService;
    private final ObjectMapper objectMapper;
    private final OutboxRepository outboxRepository;
    
    /** 
	 * Processes the restaurant step in the Saga workflow based on the PaymentProcessedEvent.
	 * This method handles inventory checks, ticket creation, and outbox event generation.
	 * It ensures idempotency and robust validation of required fields and business rules.
	 *
	 * @param event The PaymentProcessedEvent containing order details and payment status.
	 */
    @Transactional
    public void processRestaurantStep(PaymentProcessedEvent event) {
    	
        log.info("[Start] Processing restaurant step in RestaurantService for Order ID: {} with Status: {}", 
                event.orderId(), event.status());
        
        // PROACTIVE IDEMPOTENCY GUARD: Check for duplicate execution
        if (ticketRepository.existsByOrderId(event.orderId())) {
            log.debug("Idempotency Guard: Ticket already exists for Order ID: {}. Skipping processing silently.", 
                     event.orderId());
            // Exits the void method cleanly without throwing exceptions or writing to the outbox!
            return;
        }
        
        // Validate required fields and business rules
        validateRequiredFields(event);
        validateBusinessRules(event);        
        log.info("Validation passed for payment process event with order: {}", event.orderId());
        
        InventoryStatus inventoryStatus = inventoryService.verifyAndDeductStock(
        		event.itemCode(), event.quantity());
        
        /// -- Handle Inventory Status and Create Outbox Events -- ///
        switch (inventoryStatus) {
			
        	case ALLOCATED -> {
        		
				log.info("Food is available! Accepting Order: {}", event.orderId());
				
	            RestaurantTicket ticket = createAndSaveTicket(event.orderId(), RestaurantTicketStatus.PREPARING);
	            RestaurantApprovedEvent approvedEvent =  new RestaurantApprovedEvent(event.orderId(), 
	            												event.customerId(), ticket.getId());
	            
	            saveRestaurantTicketOutbox(ticket, approvedEvent, "RestaurantApproved");	            	            
			}
        	
			case INSUFFICIENT_STOCK -> {
				
				log.warn("Out of stock! Rejecting Order: {}", event.orderId());
				
				RestaurantTicket ticket = createAndSaveTicket(event.orderId(), RestaurantTicketStatus.REJECTED);
	            RestaurantRejectedEvent rejectedEvent = new RestaurantRejectedEvent(event.orderId(), 
	            												event.customerId(), 
	            												"Out of stock for item: " + event.itemCode());
	            
	            saveRestaurantTicketOutbox(ticket, rejectedEvent, "RestaurantRejected");	            
			}
			
			case ITEM_NOT_FOUND -> {
				
				log.warn("Item not found! Rejecting Order: {}", event.orderId());
				
				RestaurantTicket ticket = createAndSaveTicket(event.orderId(), RestaurantTicketStatus.REJECTED);
	            RestaurantRejectedEvent rejectedEvent = new RestaurantRejectedEvent(event.orderId(), event.customerId(), 
	                    											"Invalid item code: " + event.itemCode());
	            
	            saveRestaurantTicketOutbox(ticket, rejectedEvent, "RestaurantRejected");	            
			}			
		};
		
        log.info("[Done] Processing restaurant step in RestaurantService for Order ID: {} with Status: {}", 
                event.orderId(), event.status());

    }    
    
    /// -- Validation Methods - - ///
    /// Validates that all required fields are present in the PaymentProcessedEvent
    private void validateRequiredFields(PaymentProcessedEvent event) {
    	
    	log.info("Validating required fields.");
    	
        if (event.orderId() == null) {
            throw new IllegalArgumentException("orderId is required");
        }

        if (event.customerId() == null) {
            throw new IllegalArgumentException("customerId is required");
        }

        if (event.itemCode() == null) {
            throw new IllegalArgumentException("itemCode is required");
        }

        if (event.status() == null) {
            throw new IllegalArgumentException("status is required");
        }
    }
    
    /// Validates business rules for the PaymentProcessedEvent
    private void validateBusinessRules(PaymentProcessedEvent event) {
    	
    	log.info("Validating business logic rules.");
    	
        if (event.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }

        if (event.amount() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }

        if (event.status() != PaymentStatus.APPROVED) {
            throw new IllegalArgumentException(
                "Expected APPROVED but got " + event.status());
        }
    }
    
    /// To create a restaurant ticket for the order.
    private RestaurantTicket createAndSaveTicket(String orderId, RestaurantTicketStatus status) {
        RestaurantTicket ticket = RestaurantTicket.builder()
                .id(UUID.randomUUID().toString())
                .orderId(orderId)
                .status(status)
                .build();
        return ticketRepository.save(ticket);
    }
    
    /// To save the restaurant ticket event to the outbox for publishing to Kafka
    /// This method captures the current OpenTelemetry context and includes traceId and spanId in the outbox record for distributed tracing.
    private void saveRestaurantTicketOutbox(RestaurantTicket ticket, RestaurantEvent event, String eventType) {
    	
    	String jsonPayload = null;
    	try {
    		
            jsonPayload = objectMapper.writeValueAsString(event);
            
        } catch (Exception e) {
            throw new RuntimeException("Payment outbox serialization failed for order: " 
            							+ event.orderId(), e);
        }
    	
    	// Capture active OpenTelemetry Context Strings ---
        SpanContext activeContext = Span.current().getSpanContext();
        // Fallbacks protect local unit tests if executed outside an active telemetry container
        String traceId = activeContext.isValid() ? activeContext.getTraceId() 
        					: INVALID_TRACE_ID;
        String spanId = activeContext.isValid() ? activeContext.getSpanId() 
        					: INVALID_SPAN_ID;
        
        OutboxRecord outbox = OutboxRecord.builder()
            .aggregateId(event.orderId()) // Keep using the original order ID as the Kafka key!
            .eventType(eventType)
            .payload(jsonPayload)
            .createdTime(LocalDateTime.now())
            .traceId(traceId)
            .spanId(spanId)
            .build();

        outboxRepository.save(outbox);
        log.info("Saved outbox for restaurant ticket with payload:{}", outbox.getPayload());
    }
}
