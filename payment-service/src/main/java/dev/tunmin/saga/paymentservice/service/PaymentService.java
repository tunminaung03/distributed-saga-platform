package dev.tunmin.saga.paymentservice.service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import dev.tunmin.saga.paymentservice.domain.OutboxRecord;
import dev.tunmin.saga.paymentservice.domain.Payment;
import dev.tunmin.saga.paymentservice.domain.PaymentStatus;
import dev.tunmin.saga.paymentservice.dto.OrderCreatedEvent;
import dev.tunmin.saga.paymentservice.dto.OrderStatus;
import dev.tunmin.saga.paymentservice.dto.PaymentProcessedEvent;
import dev.tunmin.saga.paymentservice.dto.RestaurantRejectedEvent;
import dev.tunmin.saga.paymentservice.repository.OutboxRepository;
import dev.tunmin.saga.paymentservice.repository.PaymentRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * PaymentService is responsible for handling all payment-related operations in the Saga workflow.
 * It processes incoming OrderCreatedEvents to deduct funds and handles compensation logic when 
 * RestaurantPreparedEvents indicate a rejection. The service ensures idempotency and robust error handling
 * to maintain data consistency across distributed transactions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
	
	private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";
	private static final String INVALID_SPAN_ID = "0000000000000000";

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    
    /// Primary Saga handler for processing payment when a new order is created or when a compensation refund is needed
    @Transactional
    public void processPaymentSaga(OrderCreatedEvent event) {
        
    	log.info("[START] Processing payment step for Order ID: {} with input status: {}", 
                event.orderId(), event.status());
        
    	String orderId = event.orderId();
    	
        if (!OrderStatus.PENDING.equals(event.status())) {
        	
        	log.warn("Received OrderCreatedEvent with non-PENDING status: {}. for Order ID: {}.", 
						event.status(), orderId);
        	throw new IllegalArgumentException(
        	        "Expected PENDING order status but received: " + event.status());
        }
        
        // Pro-active check for Idempotency:
        Optional<Payment> existingPayment = paymentRepository.findByOrderId(orderId);        
        if (existingPayment.isPresent()) {
        	
            log.debug("Duplicate payment event detected for order {}. "
            			+ "Ignoring and skipping outbox write.", orderId);
            return; // Safely exits cleanly
        }
        
        // Safe to proceed with payment processing and outbox writing
        deductFunds(event);
        
    	log.info("[DONE] Processing payment step for Order ID: {} with input status: {}", 
                event.orderId(), event.status());

    }
    
    /// Compensation handler for when the restaurant explicitly REJECTS the ticket after payment approval
    @Transactional
    public void handleOrderCompensation(RestaurantRejectedEvent event) {
    	
        log.info("Received restaurant rejected event for Order ID: {} with reason: {}", 
                event.orderId(), event.reason());
        
        String orderId = event.orderId();
        Payment payment = paymentRepository.findByOrderId(orderId)
							.orElseThrow( 
									() -> new IllegalArgumentException(
												"Payment record for Order ID " + orderId + " not found.")
									);
        
        // IDEMPOTENCY Guard
        if (!PaymentStatus.APPROVED.equals(payment.getStatus())) {

            log.info( "Payment for Order {} is already in status {}. Ignoring duplicate compensation.",
            			orderId, payment.getStatus());
            return;
        }
        
        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);
            
        // Put actual money reversal or wallet balance refund logic here
        
        log.info("Compensation Complete! Payment for Order {} successfully REFUNDED/CANCELLED.", 
            		orderId);                
    }
    
    
    /// -- Internal helper methods for processing payment and handling compensation logic --
    
    /// processing payment and saving outbox 
    private void deductFunds(OrderCreatedEvent event) {
        
    	log.info("Deducting ${} from customer {} for order Id:{}", 
    				event.totalAmount(), event.customerId(), event.orderId());                
        
    	String orderId = event.orderId();
    	    	
        Payment payment = Payment.builder()
                .id(UUID.randomUUID().toString())
                .orderId(orderId)
                .amount(event.totalAmount())
                .status(PaymentStatus.APPROVED)
                .build();
                        
    	paymentRepository.save(payment);
    	log.info("Saved payment record to database, id :{}", payment.getId());
    	
    	savePaymentOutbox(payment, event);    	
    }
    
    /// saving outbox record
    private void savePaymentOutbox(Payment payment, OrderCreatedEvent event) {
    	
        PaymentProcessedEvent outboxEvent = new PaymentProcessedEvent(
        		payment.getOrderId(), 
        		event.customerId(), event.itemCode(), event.quantity(), 
        		payment.getAmount(), PaymentStatus.APPROVED
        );

        String jsonPayload = null;
        try {
            jsonPayload = objectMapper.writeValueAsString(outboxEvent);
            
        } catch (Exception e) {
        	
        	log.error("Failed to serialize for payment, id:{} with Order:{}", 
        				payment.getId(), payment.getOrderId());
            throw new RuntimeException("Payment outbox serialization failed for order: " 
            							+ payment.getOrderId(), e);
        }
        
        // Capture active OpenTelemetry Context Strings ---
        SpanContext activeContext = Span.current().getSpanContext();
        // Fallbacks protect local unit tests if executed outside an active telemetry container
        String traceId = activeContext.isValid() ? activeContext.getTraceId() 
        					: INVALID_TRACE_ID;
        String spanId = activeContext.isValid() ? activeContext.getSpanId() 
        					: INVALID_SPAN_ID;
        
        OutboxRecord outbox = OutboxRecord.builder()
		        .aggregateId(payment.getOrderId()) // Keep using the original order ID as the Kafka key!
		        .eventType("PaymentProcessed")
		        .payload(jsonPayload)
		        .createdTime(LocalDateTime.now())
		        .traceId(traceId)
		        .spanId(spanId)
		        .build();
        
        outboxRepository.save(outbox);
        log.info("Saved outbox for payment with payload:{}", 
        			outbox.getPayload());
    }

}