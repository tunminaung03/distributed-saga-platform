package dev.tunmin.saga.paymentservice.service;

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

import dev.tunmin.saga.paymentservice.domain.OutboxRecord;
import dev.tunmin.saga.paymentservice.dto.PaymentProcessedEvent;
import dev.tunmin.saga.paymentservice.repository.OutboxRepository;
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
     * Verifies that the background scheduling outbox processing loop short-circuits 
     * safely, avoiding unnecessary stream allocation and database mutations when the 
     * underlying queue table contains zero records.
     */
    @Test
    void publishNextPaymentOutboxBatch_doesNothing_whenNoRecords() {
        // Arrange: Stub the repository to return an empty list for chronological batch polling
        when(outboxRepo.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10)))
                .thenReturn(Collections.emptyList());

        // Act: Invoke the target background worker execution block
        publisherService.publishNextPaymentOutboxBatch();

        // Assert: Ensure no routing channels were hit and no deletions occurred
        verifyNoInteractions(streamBridge);
        verify(outboxRepo, never()).delete(any());
    }

    /**
     * Happy Path Scenario: Verifies that a valid payment outbox record is successfully pulled, 
     * translated into its corresponding record payload via Jackson 3, enriched with binary 
     * Kafka aggregation key headers, dispatched over StreamBridge, and permanently deleted from the DB.
     */
    @Test
    void publishNextPaymentOutboxBatch_processesRecord_andStagesDelete_and_setsHeaders() throws Exception {
        // Arrange: Mock out a pending payment outbox item tracking a positive approved mutation
        UUID recordId = UUID.randomUUID();
        String orderId = "order-abc-123";
        String payloadJson = "{\"orderId\":\"order-abc-123\",\"status\":\"APPROVED\"}";
        
        OutboxRecord record = OutboxRecord.builder()
                .id(recordId)
                .aggregateId(orderId)
                .eventType("PaymentProcessed")
                .payload(payloadJson)
                .build();

        when(outboxRepo.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10)))
                .thenReturn(List.of(record));

        // Stub the modern tools.jackson.databind.ObjectMapper parsing signature
        PaymentProcessedEvent mockEvent = mock(PaymentProcessedEvent.class);
        when(objectMapper.readValue(payloadJson, PaymentProcessedEvent.class))
        	.thenReturn(mockEvent);

        // Simulate successful network routing acknowledgment from the local message broker
        when(streamBridge.send(eq("publishPaymentOutboxEvents-out-0"), any(Message.class)))
        	.thenReturn(true);

        ArgumentCaptor<Message<?>> messageCaptor = ArgumentCaptor.forClass(Message.class);

        // Act: Execute the polling loop logic
        publisherService.publishNextPaymentOutboxBatch();

        // Assert: Capture and verify message structural integrity rules
        verify(streamBridge).send(eq("publishPaymentOutboxEvents-out-0"), messageCaptor.capture());
        Message<?> capturedMessage = messageCaptor.getValue();
        assertNotNull(capturedMessage);
        assertSame(mockEvent, capturedMessage.getPayload());

        // Assert: Guarantee the core business identifier is passed strictly as raw UTF-8 encoded bytes
        byte[] expectedKeyBytes = orderId.getBytes(StandardCharsets.UTF_8);
        byte[] actualKeyBytes = (byte[]) capturedMessage.getHeaders().get(KafkaHeaders.KEY);
        assertArrayEquals(expectedKeyBytes, 
        					actualKeyBytes, 
        					"Kafka message key must be mapped to raw binary byte arrays");

        // Verify: Ensure the processed row is deleted to clear it from the live FIFO queue table
        verify(outboxRepo).delete(record);
    }

    /**
     * Distributed Transactional Integrity Scenario: Verifies that if StreamBridge returns false 
     * (due to broken network bindings or offline partitions), an IllegalStateException is thrown. 
     * This forces the active transaction to roll back, protecting the outbox row from being deleted.
     */
    @Test
    void publishNextPaymentOutboxBatch_shouldThrowException_whenStreamBridgeFails() throws Exception {
        // Arrange: Staging a record bound to trigger a network exception loop
        OutboxRecord record = OutboxRecord.builder()
                .id(UUID.randomUUID())
                .aggregateId("order-fail-777")
                .eventType("PaymentProcessed")
                .payload("{}")
                .build();

        when(outboxRepo.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10)))
        	.thenReturn(List.of(record));
        when(objectMapper.readValue(anyString(), eq(PaymentProcessedEvent.class)))
        	.thenReturn(mock(PaymentProcessedEvent.class));

        // Simulate an unacknowledged broker delivery drop or channel routing failure
        when(streamBridge.send(eq("publishPaymentOutboxEvents-out-0"), any(Message.class)))
        	.thenReturn(false);

        // Act & Assert: Capture the expected execution failure
        assertThrows(IllegalStateException.class, 
        			() -> publisherService.publishNextPaymentOutboxBatch());

        // Verify: Ensure the record is NEVER deleted from the table, allowing the loop to retry later
        verify(outboxRepo, never()).delete(any());
    }
}
