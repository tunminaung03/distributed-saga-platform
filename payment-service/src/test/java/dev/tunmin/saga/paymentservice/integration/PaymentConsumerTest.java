package dev.tunmin.saga.paymentservice.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;

import dev.tunmin.saga.paymentservice.domain.Payment;
import dev.tunmin.saga.paymentservice.domain.PaymentStatus;
import dev.tunmin.saga.paymentservice.dto.OrderCreatedEvent;
import dev.tunmin.saga.paymentservice.dto.OrderStatus;
import dev.tunmin.saga.paymentservice.dto.RestaurantRejectedEvent;
import dev.tunmin.saga.paymentservice.repository.PaymentRepository;


/**
 * Integration tests for the payment processing and compensation binders.
 * 
 * This suite verifies the event-driven behavior flowing through the StreamBridge 
 * routing engine. Since StreamBridge bridges directly into the test binder layout, 
 * assertions leverage OutputDestination directly.
 */
@SpringBootTest
@Import(TestChannelBinderConfiguration.class)
class PaymentConsumerTest {

	@Autowired
    private InputDestination input;

    @Autowired
    private OutputDestination output;

    @Autowired
    private PaymentRepository paymentRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        // Clear any leftover messages from previous test executions
        output.clear();
    }

    /**
     * Consumer Happy Path Test:
     * Sending a PENDING OrderCreatedEvent to 'order-created-topic' should trigger processing,
     * persist an approved payment, and route an output event via StreamBridge.
     */
    @Test
    void processPaymentConsumer_ShouldEmitPaymentProcessedEvent_OnValidPendingInput() throws Exception {
    	// Arrange
        String orderId = "order-happy-path";
        OrderCreatedEvent inputEvent = new OrderCreatedEvent(
        		orderId, "customer-123", "BURGER_01", 
        		2, 25.50, OrderStatus.PENDING);
        Message<OrderCreatedEvent> message = MessageBuilder.withPayload(inputEvent).build();

        // Act: Fire into the input destination binder
        input.send(message, "order-created-topic");
        
        // Introduce a small defensive sleep to allow your @Scheduled outbox thread to wake up and process
        Thread.sleep(600);

        // Assert: Read directly from the configured destination topic output channel proxy
        Message<byte[]> outputMessage = output.receive(4000, "payment-processed-topic");
        assertNotNull(outputMessage, "Expected a payment event to be routed by StreamBridge");

        String payloadString = new String(outputMessage.getPayload());
        assertTrue(payloadString.contains(orderId), "Payload must contain the target order ID");
        assertTrue(payloadString.contains(PaymentStatus.APPROVED.name()), "Payload status must be APPROVED");

        // Verify Database Persistence
        Payment persisted = paymentRepository.findByOrderId(orderId).orElse(null);
        assertNotNull(persisted, "Payment record must be saved to the database");
        assertEquals(PaymentStatus.APPROVED, persisted.getStatus());
    }

    /**
     * Consumer Idempotency Guard Test:
     * If an approved payment record already exists for an order, a duplicate PENDING event
     * must short-circuit early and produce absolutely zero output messages via StreamBridge.
     */
    @Test
    void processPaymentConsumer_ShouldShortCircuitAndEmitNothing_OnDuplicateInput() throws Exception {
        // Arrange: Pre-populate the database to trigger the idempotency guard check
        String orderId = "order-idempotent-check";
        Payment existingPayment = Payment.builder()
                .id("pay-existing-123")
                .orderId(orderId)
                .amount(25.50)
                .status(PaymentStatus.APPROVED)
                .build();
        paymentRepository.save(existingPayment);

        OrderCreatedEvent duplicateEvent = new OrderCreatedEvent(
            orderId, "customer-123", "BURGER_01", 2, 25.50, OrderStatus.PENDING
        );
        Message<OrderCreatedEvent> message = MessageBuilder.withPayload(duplicateEvent).build();

        // Act: Fire into the consumer binder
        input.send(message, "order-created-topic");
        Thread.sleep(600); // Wait for the scheduled window to pass

        // Assert: Verify that absolutely no message was dispatched to the topic channel
        Message<byte[]> outputMessage = output.receive(1000, "payment-processed-topic");
        assertNull(outputMessage, "Idempotency guard failure: StreamBridge routed a duplicate event!");
    }

    /**
     * Consumer Compensation Path Test:
     * Sending a RestaurantRejectedEvent to 'restaurant-rejected-topic' triggers the 
     * compensatePayment binder, updating the existing payment status to REFUNDED.
     */
    @Test
    void compensatePaymentConsumer_ShouldRefundPayment_WhenRestaurantRejects() throws Exception {
        // Arrange: Pre-populate an APPROVED payment in the database
        String orderId = "order-refund-123";
        Payment existing = Payment.builder()
                .id("pay-999")
                .orderId(orderId)
                .amount(20.0)
                .status(PaymentStatus.APPROVED)
                .build();
        paymentRepository.save(existing);

        RestaurantRejectedEvent rejectEvent = new RestaurantRejectedEvent(orderId, "1", "Out of inventory");
        Message<RestaurantRejectedEvent> message = MessageBuilder.withPayload(rejectEvent).build();

        // Act: Send message to the compensation topic
        input.send(message, "restaurant-rejected-topic");
        Thread.sleep(200); // Brief wait for asynchronous database persistence execution

        // Assert: Verify database status successfully changed to REFUNDED
        Payment updated = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, updated.getStatus());
    }       
}