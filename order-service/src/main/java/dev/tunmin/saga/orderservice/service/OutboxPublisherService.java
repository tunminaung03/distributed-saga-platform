package dev.tunmin.saga.orderservice.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.domain.PageRequest;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.tunmin.saga.orderservice.domain.OutboxRecord;
import dev.tunmin.saga.orderservice.dto.OrderCreatedEvent;
import dev.tunmin.saga.orderservice.repository.OutboxRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class OutboxPublisherService {
	
	// Tracing and Logging Constants
	private static final String SPAN_BATCH_HEARTBEAT = "publish_batch_heartbeat";
	private static final String SPAN_PUBLISH_ORDER = "publish_order_event";
    // Spring Cloud Stream Binding Destination Constant
	private static final String BINDING_ORDER_OUTBOX = "publishOrderOutboxEvents-out-0";
    // W3C Trace Context format matching: version-traceId-spanId-traceFlags
    private static final String W3C_TRACEPARENT_FORMAT = "00-%s-%s-01";
    private static final String HEADER_TRACEPARENT = "traceparent";
	
	private final OutboxRepository outboxRepo;
	private final StreamBridge streamBridge;	
	private final ObjectMapper objectMapper;
	private final int batchSize;	
	private final Tracer micrometerTracer;
	
	public OutboxPublisherService(
            OutboxRepository outboxRepo,
            StreamBridge streamBridge,            
            ObjectMapper objectMapper,
            @Value("${outbox.batch-size:10}") int batchSize,            
            Tracer micrometerTracer) {
		
        this.outboxRepo = outboxRepo;
        this.streamBridge = streamBridge;        
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;        
        this.micrometerTracer = micrometerTracer;
    }		
	
	/**
	 * 1. [START] Poller enters `@Transactional` Method.
	   │
	   ├──> 2. Database `DELETE` query runs. 
	   │       (*NOTE: This is only staged in database RAM memory. It is NOT permanently saved yet!*)
	   │
	   ├──> 3. Method returns the List object to Spring.
	   │
	   ├──> 4. Spring opens a staged transaction on Kafka using `restaurant-tx-`.
	   │
	   ├──> 5. Spring sends the events to the Kafka cluster brokers over the network.
	   │
	   ▼
	[THE CRITICAL DECISION POINT]
	   │
	   ├───> CASE A: Kafka network send SUCCESS! (The ACK arrives)
	   │     │
	   │     ├──> Spring sends `COMMIT` to Kafka (messages become visible to consumers).
	   │     └──> Spring sends `COMMIT` to the Database (the row deletion becomes permanent).
	   │
	   └───> CASE B: Kafka network send FAILS! (Network drop / Broker offline)
	         │
	         ├──> Spring sends `ABORT` to Kafka (staged messages are erased from the broker).
	         └──> Spring triggers a database `ROLLBACK`. 
	              (*The deletion is reversed! The rows reappear in the DB table instantly.*)
	              
	  [See] the prop => spring.cloud.stream.kafka.binder.transaction.transactionIdPrefix            
	 */
	 @Transactional
     @Scheduled(fixedDelayString = "${app.outbox.polling-delay-ms:500}")
	 public void publishNextOrderOutboxBatch() {
		 
		 List<OutboxRecord> batch = outboxRepo.findByOrderByCreatedTimeAsc(PageRequest.of(0, batchSize));
		 // If the batch is empty, exit early to avoid unnecessary processing.
		 if (batch.isEmpty()) {
			 return; 
		 }
		 
		 // Log the batch size and trace context for observability
		 logBatchHeartbeat(batch);
		 
		 // Process each outbox record in the batch
		 for (OutboxRecord record : batch) {
			 processAndPublishRecord(record);
		 }
	 }
	 
	 /// Logs the batch size and trace context for the current outbox processing cycle.
	 private void logBatchHeartbeat(List<OutboxRecord> batch) {
    	
		 TraceContext logParentContext = buildTraceContext(batch.get(0).getTraceId(), 
				 											batch.get(0).getSpanId());

		 Span logSpan = micrometerTracer.spanBuilder()
                .setParent(logParentContext)
                .name(SPAN_BATCH_HEARTBEAT)
                .start();
        
		 try (Tracer.SpanInScope _ = micrometerTracer.withSpan(logSpan)) {
			 
			 log.info("Processing outbox execution pipeline. Batch size picked up: {}", 
					 	batch.size());
		 } finally {
			 logSpan.end();
		 }
	 }
	 
	 /// Converts the outbox record to its corresponding event type,
	 /// restores the trace context, and publishes the event to Kafka.
	 private void processAndPublishRecord(OutboxRecord record) {
		 
		 OrderCreatedEvent eventPayload = this.convertToEvent(record);
		 log.debug("Converted record id={} to event class={}", 
				 	record.getId(), eventPayload.getClass().getSimpleName());
		 
		 // Restore the trace context from the outbox record's traceId and spanId.
		 TraceContext restoredParentContext = buildTraceContext(record.getTraceId(), 
				 												record.getSpanId());
		 // Start a new span for the outbox publishing operation, using the restored trace context as the parent.
		 Span outboxSpan = micrometerTracer.spanBuilder()
                .setParent(restoredParentContext)
                .name(SPAN_PUBLISH_ORDER)
                .start();
        
		 try (Tracer.SpanInScope _ = micrometerTracer.withSpan(outboxSpan)) {	// Ensure the span is active for the duration of this block
			 
			 Message<?> kafkaMessage = createKafkaMessage(eventPayload, record, outboxSpan);
			 
			 // Send the message to Kafka via StreamBridge. 
			 // If sending fails, throw an exception to trigger a transaction rollback.
			 boolean sent = streamBridge.send(BINDING_ORDER_OUTBOX, kafkaMessage);
			 if (!sent) {
				 throw new IllegalStateException("Failed to publish outbox record " + record.getId());
			 }

			 log.debug("Staging delete for outbox id={}", 
					 	record.getId());
			 outboxRepo.delete(record);
            
			 log.info("Published and deleted outbox record id={} | TraceID: {}", 
            			record.getId(), record.getTraceId());
            
		 } catch (Exception e) {
			 
			 outboxSpan.error(e);	// Log the error in the span for observability
			 throw e; 				// Rethrow for transaction rollback and ensure the outbox record remains in the database for retry
		 } finally {
			 
			 outboxSpan.end();
		 }
	 }
	 
	 /// Converts the JSON payload of the outbox record into its corresponding event class.
	 public OrderCreatedEvent convertToEvent(OutboxRecord record) {
		 
		 try {
			 return switch (record.getEventType()) {
			 	
			 	case "OrderCreated" -> 
			 			objectMapper.readValue(record.getPayload(), OrderCreatedEvent.class);
			 	
			 	default -> {
                    log.warn("Unknown eventType for outbox id={} eventType={}", 
                    			record.getId(), record.getEventType());
                    throw new IllegalArgumentException("Unknown target event type: " + record.getEventType());
                }
			 };
		 } catch (Exception e) {
			 log.error("Failed to deserialize outbox payload for record id={} eventType={}", 
					 	record.getId(), record.getEventType(), e);
			 throw new RuntimeException("Failed to cleanly deserialize outbox payload for record ID: " + record.getId(), 
					 					e);
		 }
	 }
	 
	 /// Builds a Micrometer TraceContext from the provided traceId and spanId.
	 private TraceContext buildTraceContext(String traceId, String spanId) {
		 return micrometerTracer.traceContextBuilder()
                .traceId(traceId)
                .spanId(spanId)
                .sampled(true)
                .build();
	 }
	 
	 /// Creates a Kafka Message with the appropriate payload and headers, 
	 /// including the W3C traceparent header for distributed tracing.
	 private Message<?> createKafkaMessage(OrderCreatedEvent payload, 
			 								OutboxRecord record, Span span) {
		 
		 String w3cTraceParentHeader = String.format(W3C_TRACEPARENT_FORMAT, 
                span.context().traceId(), 
                span.context().spanId());

		 return MessageBuilder.withPayload(payload)
                .setHeader(HEADER_TRACEPARENT, 
                			w3cTraceParentHeader)
                .setHeader(KafkaHeaders.KEY, 
                			record.getAggregateId().getBytes(StandardCharsets.UTF_8))
                .build();
    }
}