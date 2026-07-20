package dev.tunmin.saga.orderservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.tunmin.saga.orderservice.domain.Order;
import dev.tunmin.saga.orderservice.domain.OrderStatus;
import dev.tunmin.saga.orderservice.domain.OutboxRecord;
import dev.tunmin.saga.orderservice.dto.CreateOrderRequest;
import dev.tunmin.saga.orderservice.dto.RestaurantApprovedEvent;
import dev.tunmin.saga.orderservice.dto.RestaurantRejectedEvent;
import dev.tunmin.saga.orderservice.repository.OrderRepository;
import dev.tunmin.saga.orderservice.repository.OutboxRepository;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
public class OrderServiceTest {
	
    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private ObjectMapper objectMapper; // Fully compliant with Jackson 3 namespace specifications

    @InjectMocks
    private OrderService orderService;

    /**
     * Happy Path Scenario: Verifies that when a valid order request arrives alongside an 
     * authenticated user identity, a PENDING order is generated using the secure ID, 
     * saved to DB, and a corresponding tracking entry is staged inside the Outbox table.
     */
    @Test
    void createOrder_shouldPersistOrderAndStageOutbox_whenSecureIdentityIsProvided() throws Exception {
        // Arrange
        String mockAuthenticatedUserId = "customer-789";
        CreateOrderRequest request = new CreateOrderRequest("customer-789", 50.00, 
        														"PIZZA_01", 2);

        // REMOVED: userGrpcClient.validateUser mocking behaviors
        when(orderRepository.save(any(Order.class)))
        	.thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.writeValueAsString(any()))
        	.thenReturn("{\"mockSerializedOrder\":true}");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<OutboxRecord> outboxCaptor = ArgumentCaptor.forClass(OutboxRecord.class);

        // Act - Updated to pass the explicit authenticated userId
        Order createdOrder = orderService.createOrder(mockAuthenticatedUserId, request);

        // Assert: Verify generated order structural integrity boundaries
        assertNotNull(createdOrder);
        verify(orderRepository).save(orderCaptor.capture());
        Order savedOrder = orderCaptor.getValue();
        
        assertEquals("customer-789", savedOrder.getCustomerId()); 
        assertEquals("PIZZA_01", savedOrder.getItemCode());
        assertEquals(2, savedOrder.getQuantity());
        assertEquals(50.00, savedOrder.getTotalAmount());
        assertEquals(OrderStatus.PENDING, savedOrder.getStatus());

        // Assert: Verify accompanying transactional outbox payload configuration metadata
        verify(outboxRepository).save(outboxCaptor.capture());
        OutboxRecord savedOutbox = outboxCaptor.getValue();
        assertNotNull(savedOutbox);
        assertEquals(savedOrder.getId(), savedOutbox.getAggregateId());
        assertEquals("OrderCreated", savedOutbox.getEventType());
    }

    /**
     * Saga Step Idempotency Scenario: Verifies that when a RestaurantApprovedEvent is processed,
     * the system checks if the order is already SUCCESS. If true, it must short-circuit early 
     * via the idempotency check and skip duplicate database save steps.
     */
    @Test
    void confirmOrder_shouldShortCircuit_whenOrderIsAlreadySuccess() {
        // Arrange
        String orderId = "order-success-id";
        RestaurantApprovedEvent event = new RestaurantApprovedEvent(orderId, "1", "1");

        when(orderRepository.existsByIdAndStatus(orderId, OrderStatus.SUCCESS))
        	.thenReturn(true);

        // Act
        orderService.confirmOrder(event);

        // Assert: Core repository find and save execution tracking must be completely bypassed
        verify(orderRepository, never())
        	.findById(anyString());
        verify(orderRepository, never())
        	.save(any(Order.class));
    }

    /**
     * Saga Handler Path: Verifies that when an order is in PENDING state and a 
     * RestaurantApprovedEvent is received, the transaction status maps to SUCCESS.
     */
    @Test
    void confirmOrder_shouldTransitionStatusToSuccess_whenOrderIsPending() {
        // Arrange
        String orderId = "order-pending-id";
        RestaurantApprovedEvent event = new RestaurantApprovedEvent(orderId, "1", "1");

        Order existingOrder = Order.builder()
                .id(orderId)
                .status(OrderStatus.PENDING)
                .build();

        when(orderRepository.existsByIdAndStatus(orderId, OrderStatus.SUCCESS))
        	.thenReturn(false);
        when(orderRepository.findById(orderId))
                .thenReturn(Optional.of(existingOrder));

        // Act
        orderService.confirmOrder(event);

        // Assert: Validate tracking updates are pushed back to the schema
        verify(orderRepository)
        	.save(existingOrder);
        assertEquals(OrderStatus.SUCCESS, existingOrder.getStatus());
    }

    /**
     * Compensation Step Idempotency Scenario: Verifies that when a RestaurantRejectedEvent arrives 
     * for an order that is already CANCELLED, the processing loop exits cleanly via the guard index check.
     */
    @Test
    void cancelOrder_shouldShortCircuit_whenOrderIsAlreadyCancelled() {
        // Arrange
        String orderId = "order-cancelled-id";
        RestaurantRejectedEvent event = new RestaurantRejectedEvent(orderId, "1", "Out of Stock");

        when(orderRepository.existsByIdAndStatus(orderId, OrderStatus.CANCELLED))
        	.thenReturn(true);

        // Act
        orderService.cancelOrder(event);

        // Assert: Avoids loading or rewriting the database layer repeatedly
        verify(orderRepository, never())
        	.findById(anyString());
        verify(orderRepository, never())
        	.save(any(Order.class));
    }
}
