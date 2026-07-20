package dev.tunmin.saga.restaurantservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

import dev.tunmin.saga.restaurantservice.domain.OutboxRecord;
import dev.tunmin.saga.restaurantservice.dto.RestaurantApprovedEvent;
import dev.tunmin.saga.restaurantservice.dto.RestaurantRejectedEvent;
import dev.tunmin.saga.restaurantservice.repository.OutboxRepository;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests isolating the background processing loop within OutboxPublisherService.
 * 
 * Verifies chronological FIFO queue database processing, modern Jackson 3 payload 
 * transformation maps, byte array Kafka partition key routing, and transactional 
 * validation error bubble-ups.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private OutboxRepository outboxRepo;

    @Mock
    private ObjectMapper objectMapper;
    
    @Mock
    private StreamBridge streamBridge;
    
    // --- Pure Mockito Telemetry Chain Injection ---
    @Mock 
    private OpenTelemetry openTelemetry;
    @Mock 
    private Tracer tracer;
    @Mock 
    private SpanBuilder spanBuilder;
    @Mock 
    private Span span;
    @Mock 
    private Scope scope; // Mocks the auto-closeable span context lifecycle execution
    @Mock
    private io.micrometer.tracing.Tracer micrometerTracer;


    private OutboxPublisherService publisherService;
    
    @BeforeEach
    void setUp() {
    	
    	// Enforce lenient stubs so Mockito allows fluent nested invocations without strict ordering crashes
        lenient().when(openTelemetry.getTracer(anyString())).thenReturn(tracer);
        lenient().when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.setParent(any(Context.class))).thenReturn(spanBuilder);
        lenient().when(spanBuilder.startSpan()).thenReturn(span);
        lenient().when(span.makeCurrent()).thenReturn(scope);
    	
        // Explicit instantiation allows passing primitive integers (like batchSize=10) 
        // that Mockito's automatic @InjectMocks cannot evaluate natively.
        publisherService = new OutboxPublisherService(
        						outboxRepo, 
        						streamBridge, 
        						objectMapper, 
        						10,
        						micrometerTracer);
    }

    /**
     * Verifies that when the outbox repository tracks zero elements, the scheduling worker 
     * short-circuits early to protect execution resources and avoids database row modifications.
     */
    @Test
    void publishNextRestaurantOutboxBatch_doesNothing_whenNoRecords() {
        // Arrange
        when(outboxRepo.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10)))
                .thenReturn(Collections.emptyList());

        // Act
        publisherService.publishNextRestaurantOutboxBatch();

        // Assert
        verifyNoInteractions(streamBridge);
        verify(outboxRepo, never()).delete(any());
    }

    /**
     * Happy Path Scenario (Approved): Verifies that a RestaurantApproved outbox record is 
     * successfully compiled, mapped into raw text bytes headers, dispatched over StreamBridge 
     * onto the core topic, and permanently deleted from the schema queue.
     */
    @Test
    void publishNextRestaurantOutboxBatch_forApprovedRecord_setsHeaders_andDeletesRecord() throws Exception {
        // Arrange
        UUID recordId = UUID.randomUUID();
        String aggregateId = "order-123";
        String payloadJson = "{\"orderId\":\"order-123\",\"ticketId\":\"t-1\"}";
        
        OutboxRecord record = OutboxRecord.builder()
                .id(recordId)
                .aggregateId(aggregateId)
                .eventType("RestaurantApproved")
                .payload(payloadJson)
                .build();

        when(outboxRepo.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10)))
                .thenReturn(List.of(record));

        RestaurantApprovedEvent mockEvent = mock(RestaurantApprovedEvent.class);
        when(objectMapper.readValue(payloadJson, RestaurantApprovedEvent.class))
        	.thenReturn(mockEvent);
        when(streamBridge.send(eq("restaurant-approved-topic"), any(Message.class)))
        	.thenReturn(true);

        ArgumentCaptor<Message<?>> messageCaptor = ArgumentCaptor.forClass(Message.class);

        // Act
        publisherService.publishNextRestaurantOutboxBatch();

        // Assert: Parse message structure metadata properties
        verify(streamBridge)
        	.send(eq("restaurant-approved-topic"), messageCaptor.capture());
        Message<?> capturedMessage = messageCaptor.getValue();
        assertNotNull(capturedMessage);
        assertSame(mockEvent, capturedMessage.getPayload());

        // Assert: Verify your custom binary sequence key formatting contract
        byte[] expectedKeyBytes = aggregateId.getBytes(StandardCharsets.UTF_8);
        byte[] actualKeyBytes = (byte[]) capturedMessage.getHeaders().get(KafkaHeaders.KEY);
        assertArrayEquals(expectedKeyBytes, actualKeyBytes);

        // Verify database row deletion execution
        verify(outboxRepo).delete(record);
    }

    /**
     * Happy Path Scenario (Rejected): Verifies that a RestaurantRejected outbox record is 
     * successfully compiled and dispatched using the precise same underlying StreamBridge channel 
     * conveyor belt, preserving unified processing.
     */
    @Test
    void publishNextRestaurantOutboxBatch_forRejectedRecord_setsRoutingHeader_andDeletesRecord() throws Exception {
        // Arrange
        UUID recordId = UUID.randomUUID();
        String aggregateId = "order-456";
        String payloadJson = "{\"orderId\":\"order-456\",\"reason\":\"no-stock\"}";
        
        OutboxRecord record = OutboxRecord.builder()
                .id(recordId)
                .aggregateId(aggregateId)
                .eventType("RestaurantRejected")
                .payload(payloadJson)
                .build();

        when(outboxRepo.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10)))
                .thenReturn(List.of(record));

        RestaurantRejectedEvent mockEvent = mock(RestaurantRejectedEvent.class);
        when(objectMapper.readValue(payloadJson, RestaurantRejectedEvent.class))
        	.thenReturn(mockEvent);
        when(streamBridge.send(eq("restaurant-rejected-topic"), any(Message.class)))
        	.thenReturn(true);

        // Act
        publisherService.publishNextRestaurantOutboxBatch();

        // Verify
        verify(streamBridge).send(eq("restaurant-rejected-topic"), any(Message.class));
        verify(outboxRepo).delete(record);
    }

    /**
     * Distributed Transactional Fault Scenario: Verifies that if Jackson 3 JSON mapping 
     * operations crash or fail processing bounds, the exception propagates unhandled. 
     * This signals a database transaction rollback immediately, protecting row states.
     */
    @Test
    void publishNextRestaurantOutboxBatch_whenConversionFails_noDeleteAndExceptionPropagated() throws Exception {
        // Arrange
        String payloadJson = "{bad-json}";
        OutboxRecord record = OutboxRecord.builder()
                .id(UUID.randomUUID())
                .aggregateId("order-err")
                .eventType("RestaurantApproved")
                .payload(payloadJson)
                .build();

        when(outboxRepo.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10)))
                .thenReturn(List.of(record));

        // Force Jackson 3 parsing execution parameters to throw an error object
        when(objectMapper.readValue(payloadJson, RestaurantApprovedEvent.class))
                .thenThrow(new RuntimeException("deserilization fail"));

        // Act & Assert
        RuntimeException ex = assertThrows(RuntimeException.class, () -> 
            publisherService.publishNextRestaurantOutboxBatch()
        );
        
        assertTrue(ex.getMessage().contains("Failed to deserialize outbox payload for record"));        

        // Verify transactional rollback safety: the record must NEVER be deleted from the database
        verify(outboxRepo, never()).delete(any());
        verifyNoInteractions(streamBridge);
    }
}
