package dev.tunmin.saga.orderservice.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

import dev.tunmin.saga.orderservice.domain.OutboxRecord;
import dev.tunmin.saga.orderservice.dto.OrderCreatedEvent;
import dev.tunmin.saga.orderservice.repository.OutboxRepository;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the OutboxPublisherService within the payment-service layer.
 * 
 * Validates chronological database batch polling loops, modern Jackson 3 
 * serialization contracts, raw byte-array Kafka key metadata transformations, 
 * and transactional safety rollbacks tied to StreamBridge acknowledgments.
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
        							outboxRepo, streamBridge, 
        							objectMapper, 
        							10, micrometerTracer);
    }

    
    /**
     * Verifies that the publishing loop short-circuits safely and performs 
     * no database deletion operations when the outbox table is empty.
     */
    @Test
    void publishNextOrderOutboxBatch_doesNothing_whenNoRecords() {
        // Arrange
        when(outboxRepo.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10)))
                .thenReturn(Collections.emptyList());

        // Act
        publisherService.publishNextOrderOutboxBatch();

        // Assert
        verifyNoInteractions(streamBridge);
        verify(outboxRepo, never()).delete(any());                
    }

    /**
     * Verifies that a valid outbox record is successfully pulled from the database, 
     * deserialized, wrapped into a Message with appropriate byte headers, 
     * dispatched via StreamBridge, and hard-deleted from the persistence layer.
     */
    @Test
    void publishNextOrderOutboxBatch_processesRecord_andStagesDelete_and_setsHeaders() throws Exception {
        // Arrange
        UUID recordId = UUID.randomUUID();
        String aggregateId = "order-123";
        String payloadJson = "{\"orderId\":\"order-123\"}";
        
        OutboxRecord record = OutboxRecord.builder()
                .id(recordId)
                .aggregateId(aggregateId)
                .eventType("OrderCreated")
                .payload(payloadJson)
                .build();

        when(outboxRepo.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10)))
                .thenReturn(List.of(record));

        // Mock Jackson 3 parsing execution
        OrderCreatedEvent mockEvent = mock(OrderCreatedEvent.class);
        when(objectMapper.readValue(payloadJson, OrderCreatedEvent.class)).thenReturn(mockEvent);

        // CRITICAL: StreamBridge must return true to simulate successful transmission broker ACKs
        when(streamBridge.send(eq("publishOrderOutboxEvents-out-0"), any(Message.class))).thenReturn(true);

        // Capture the message to assert the headers match your byte transformations
        ArgumentCaptor<Message<?>> messageCaptor = ArgumentCaptor.forClass(Message.class);

        // Act
        publisherService.publishNextOrderOutboxBatch();

        // Assert: Capture and parse message formatting bounds
        verify(streamBridge).send(eq("publishOrderOutboxEvents-out-0"), messageCaptor.capture());
        Message<?> capturedMessage = messageCaptor.getValue();
        assertNotNull(capturedMessage);
        assertSame(mockEvent, capturedMessage.getPayload());

        // Assert: Verify your byte-array key conversion matches the production baseline
        byte[] expectedKeyBytes = aggregateId.getBytes(StandardCharsets.UTF_8);
        byte[] actualKeyBytes = (byte[]) capturedMessage.getHeaders().get(KafkaHeaders.KEY);
        assertArrayEquals(expectedKeyBytes, actualKeyBytes, "Kafka key must be stored as raw encoded bytes");

        // Verify: Ensure record deletion is cleanly finalized on transaction commits
        verify(outboxRepo).delete(record);
    }

    /**
     * Verifies that if StreamBridge returns false (indicating a delivery failure), 
     * an IllegalStateException is thrown to naturally bubble up and trigger a database transaction rollback.
     */
    @Test
    void publishNextOrderOutboxBatch_shouldThrowException_whenStreamBridgeFails() throws Exception {
        // Arrange
        OutboxRecord record = OutboxRecord.builder()
                .id(UUID.randomUUID())
                .aggregateId("order-fail")
                .eventType("OrderCreated")
                .payload("{}")
                .build();

        when(outboxRepo.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10)))
                .thenReturn(List.of(record));
        when(objectMapper.readValue(anyString(), eq(OrderCreatedEvent.class))).thenReturn(mock(OrderCreatedEvent.class));

        // Simulate a broken binder connection or partition timeout crash
        when(streamBridge.send(eq("publishOrderOutboxEvents-out-0"), any(Message.class))).thenReturn(false);

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> publisherService.publishNextOrderOutboxBatch());

        // Verify database transactional safety rules: Row must never be deleted if publishing breaks
        verify(outboxRepo, never()).delete(any());
    }
}