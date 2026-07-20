package dev.tunmin.saga.restaurantservice.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.tunmin.saga.restaurantservice.dto.PaymentProcessedEvent;
import dev.tunmin.saga.restaurantservice.dto.PaymentStatus;
import dev.tunmin.saga.restaurantservice.service.RestaurantService;

/**
 * Integration tests for the restaurant-service asynchronous consumer boundaries.
 * 
 * Symmetrical to OrderConsumerTest and PaymentConsumerTest, this class verifies 
 * that the Spring Cloud Stream inbound topic binders correctly intercept broker 
 * messages and delegate them straight to the core execution service layer.
 */
@SpringBootTest
@Import(TestChannelBinderConfiguration.class)
class RestaurantConsumerTest {

    @Autowired
    private InputDestination input;
    
    @MockitoBean
    private RestaurantService restaurantService;
    
    /**
     * Inbound Binder Path: Verifies that a valid message landing on the 
     * 'payment-processed-topic' triggers the underlying functional bean 
     * stream consumer channel mapping successfully.
     */
    @Test
    void consumerShouldInvokeRestaurantService_forApprovedEvent() {
        // Arrange
        PaymentProcessedEvent paidEvent = new PaymentProcessedEvent(
                "order-xyz", "customer-1", 
                "BURGER_01", 2, 25.0, 
                PaymentStatus.APPROVED
        );

        // Act: Deliver message straight into the stream binder simulation loop
        input.send(MessageBuilder.withPayload(paidEvent).build(), "payment-processed-topic");
    
        // Assert: Confirm framework routing successfully invoked your app service on its Virtual Thread
        verify(restaurantService, timeout(1000)).processRestaurantStep(any(PaymentProcessedEvent.class));
    }

    /**
     * Boundary Path: Verifies that even if an upstream component drops a payment event with an 
     * unexpected status, the binder infrastructure successfully extracts the context packet and 
     * transfers it to the service layer to trigger internal validation exception policies.
     */
    @Test
    void consumerShouldInvokeRestaurantService_evenWhenPaymentStatusIsFailed() {
        // Arrange
        PaymentProcessedEvent failedEvent = new PaymentProcessedEvent(
                "order-err", "customer-2", 
                "ICE_CREAM_02", 1, 5.0, 
                PaymentStatus.FAILED
        );

        // Act
        input.send(MessageBuilder.withPayload(failedEvent).build(), "payment-processed-topic");
    
        // Assert
        verify(restaurantService, timeout(1000)).processRestaurantStep(any(PaymentProcessedEvent.class));
    }
}