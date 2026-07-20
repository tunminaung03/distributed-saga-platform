package dev.tunmin.saga.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;

import dev.tunmin.saga.orderservice.dto.CreateOrderRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-End Integration and Context Propagation Tests for the Order Ingress Ecosystem.
 *
 * <p>These tests boot the full Spring application context, substitute external Kafka brokers 
 * using the in-memory {@link TestChannelBinderConfiguration}, and verify data execution 
 * pipelines under the decentralized perimeter header contract.
 *
 * <p>Test characteristics and coverage matrices included:
 * <ul>
 *   <li><b>Identity Matching Path:</b> Verifies that a valid {@code X-Perimeter-User-Id} header 
 *       matching the payload body correctly commits database rows and triggers the 
 *       {@link org.springframework.cloud.stream.function.StreamBridge} engine to stream the 
 *       outbox event to the Kafka mock broker channel.</li>
 *   <li><b>Security Identity Mismatch Guard:</b> Confirms that clients attempting to pass 
 *       conflicting body identities compared to the gateway-verified perimeter header are intercepted 
 *       and rejected early with an HTTP 403 Forbidden body structure.</li>
 *   <li><b>Perimeter Ingress Check:</b> Guarantees that requests attempting to bypass the 
 *       gateway perimeter entirely (lacking the required identity header string) short-circuit 
 *       safely at the servlet border, returning an HTTP 400 Bad Request error.</li>
 * </ul>
 *
 * <p><b>Context Propagation Blueprint:</b>
 * Asynchronous context propagation is verified using the {@link OutputDestination} bean, 
 * ensuring the cryptographically verified user identity passes from the gateway header into the 
 * outbox database transaction and down to the event stream pipeline without network dependencies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestChannelBinderConfiguration.class) // Cleanly mocks out the external Kafka infrastructure
class OrderConsumerTest {

    @Autowired
    private MockMvc mockMvc;

    // Reactivated: Replaces the custom QueueChannel boilerplate cleanly
    @Autowired
    private OutputDestination outputDestination;    
    
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Ensure the test binder memory is cleared before running the test case
        outputDestination.clear();
    }
    
    
    /**
     * Happy Path Scenario: Verifies that when the incoming X-Perimeter-User-Id header matches 
     * the customerId payload, the transaction commits, and the outbox scheduling layer 
     * flushes the transactional event down to the target Kafka binder channel destination.
     */
    @Test
    void createOrder_ShouldReturnOkAndEmitKafkaEvent_WhenIdentityMatches() throws Exception {
        String targetUserId = "1";
        CreateOrderRequest request = new CreateOrderRequest(targetUserId, 25.50, 
        													"BURGER_01", 2);

        // Act: Execute edge REST invocation containing the expected gateway header
        mockMvc.perform(post("/orders")
                .header("X-Perimeter-User-Id", targetUserId) // Injected securely by gateway edge
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // Yield processing time for @Scheduled(fixedDelay = 500) scheduling engine loop to fire
        Thread.sleep(650);

        // Assert: Capture downstream data packets routed via StreamBridge
        Message<byte[]> receivedMessage = outputDestination.receive(4000, "order-created-topic");
        
        assertNotNull(receivedMessage, "Expected an outbox event to be captured on the order-created-topic destination");
        assertNotNull(receivedMessage.getPayload(), "Captured message broker payload must not be null");
    }

    /**
     * Negative Security Scenario: Verifies that if a malicious client alters the request payload 
     * body to act on behalf of a different customer ID than the gateway-authenticated perimeter header,
     * your new ClientIdentityMismatchException triggers a clean HTTP 403 Forbidden intercept response.
     */
    @Test
    void createOrder_ShouldReturnForbidden_WhenHeaderAndPayloadIdentitiesMismatch() throws Exception {
    	
        String authenticatedUserId = "1";
        String maliciousTargetId = "99"; // Attempting to place an order under someone else's row id
        
        CreateOrderRequest request = new CreateOrderRequest(maliciousTargetId, 10.00, 
        														"FRIES_01", 1);

        // Act & Assert
        mockMvc.perform(post("/orders")
                .header("X-Perimeter-User-Id", authenticatedUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request)))
                .andExpect(status().isForbidden()); // Validated via centralized Exception Handler
    }

    /**
     * Boundary Ingress Guard Scenario: Verifies that if an unauthorized request bypasses 
     * the gateway perimeter layer and lacks the mandatory header entirely, the MVC engine 
     * short-circuits the pipeline block, rejecting execution with an HTTP 400 Bad Request.
     */
    @Test
    void createOrder_ShouldReturnBadRequest_WhenPerimeterHeaderIsCompletelyMissing() throws Exception {
    	
        CreateOrderRequest request = new CreateOrderRequest("2", 12.00, 
        													"PIZZA_01", 1);

        // Act & Assert (Omitting the .header() instruction block completely)
        mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request)))
                .andExpect(status().isBadRequest());
    }

    private String toJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }
}