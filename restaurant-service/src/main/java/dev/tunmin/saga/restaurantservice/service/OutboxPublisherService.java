package dev.tunmin.saga.restaurantservice.service;

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

import dev.tunmin.saga.restaurantservice.domain.OutboxRecord;
import dev.tunmin.saga.restaurantservice.dto.RestaurantApprovedEvent;
import dev.tunmin.saga.restaurantservice.dto.RestaurantEvent;
import dev.tunmin.saga.restaurantservice.dto.RestaurantRejectedEvent;
import dev.tunmin.saga.restaurantservice.repository.OutboxRepository;
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
    private static final String W3C_TRACEPARENT_FORMAT = "00-%s-%s-01";
    private static final String HEADER_TRACEPARENT = "traceparent";
    
    private static final String TOPIC_RESTAURANT_APPROVED = "restaurant-approved-topic";
    private static final String TOPIC_RESTAURANT_REJECTED = "restaurant-rejected-topic";
    
    private static final String SPAN_RESTAURANT_APPROVED = "publish_restaurant_approved_event";
    private static final String SPAN_RESTAURANT_REJECTED = "publish_restaurant_rejected_event";
    private static final String SPAN_BATCH_HEARTBEAT = "publish_batch_heartbeat";
	
	private final OutboxRepository outboxRepo;
	private StreamBridge streamBridge;	
	private final ObjectMapper objectMapper;		
	private final int batchSize;
	private final Tracer micrometerTracer;
	
	private record EventTarget(String topic, String spanName) {}
	
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
	
	
	/// ### Spring Batch Transaction Cycle Flow
    /// 
    /// 1. **[START]** Poller enters `@Transactional` loop context.
    ///    * Database fetches up to N records using `findByOrderByCreatedTimeAsc`.
    /// 
    /// 2. **[OBSERVABILITY]** `logBatchHeartbeat(batch)` runs within a temporary logging span scope.
    /// 
    /// 3. **[LOOP EXECUTOR]** Iterates through each `OutboxRecord` sequentially in the batch:
    ///    * **3a.** Converts database text payload to a domain Event via Jackson.
    ///    * **3b.** Starts a fresh Micrometer Span inheriting the record's tracking context.
    ///    * **3c.** `streamBridge.send()` pushes the event payload to the Kafka cluster broker network.
    ///      _(If your binder uses transactional id prefixes, Spring stages a Kafka transaction here)_
    ///    * **3d.** `outboxRepo.delete(record)` stages the row deletion.
    ///      _(NOTE: This is only staged in DB RAM. It is NOT permanently saved yet!)_
    /// 
    /// ### The Critical Transaction Decision Point
    /// 
    /// * **CASE A: The Entire Loop Completes Successfully**
    ///   * Spring pushes a structural `COMMIT` to Kafka (messages become permanently visible to consumers).
    ///   * Spring pushes a structural `COMMIT` to the Database (all row deletions become permanent simultaneously).
    /// 
    /// * **CASE B: ANY Error Occurs Inside the Loop** _(e.g., Kafka offline, Jackson parsing crashes)_
    ///   * The caught exception is immediately rethrown (`throw e`).
    ///   * Spring pushes an `ABORT` signal to Kafka (staged messages are erased from brokers).
    ///   * Spring triggers a physical Database `ROLLBACK`.
    ///     _(All row deletions for the entire batch are reversed! The rows reappear in the table instantly.)_
    /// 
    /// _Reference Binding Pattern:_ `spring.cloud.stream.kafka.binder.transaction.transactionIdPrefix`
    @Transactional
    @Scheduled(fixedDelayString = "${app.outbox.polling-delay-ms:500}")
    public void publishNextRestaurantOutboxBatch() {
    	
        List<OutboxRecord> batch = outboxRepo.findByOrderByCreatedTimeAsc(
        										PageRequest.of(0, batchSize));      
        if (batch.isEmpty()) {
            return; 
        }
        
		// Log the batch size and trace context for observability
        logBatchHeartbeat(batch);

        for (OutboxRecord record : batch) {
            processAndPublishRecord(record);
        }
    }
    
    /// Logs the batch size and trace context for the current outbox processing cycle.
    /// This helps in monitoring and debugging the outbox processing pipeline.
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
  	
  	/// Processes an individual outbox record: converts it to an event, determines the target Kafka topic, 
  	/// reconstructs the tracing context, and publishes the event to Kafka. 
  	/// If successful, the outbox record is deleted from the database.
    private void processAndPublishRecord(OutboxRecord record) {
    	
        RestaurantEvent eventPayload = this.convertToEvent(record);
        log.debug("Converted record id={} to event class={}", 
        			record.getId(), eventPayload.getClass().getSimpleName());
                    
        // Evaluate target destination and span semantic metadata
        EventTarget target = resolveEventTarget(eventPayload);                                    
        
        // Reconstruct the tracing context tracking information
        TraceContext restoredParentContext = buildTraceContext(record.getTraceId(), 
        														record.getSpanId());
        
        Span outboxSpan = micrometerTracer.spanBuilder()
                .setParent(restoredParentContext)
                .name(target.spanName())
                .start();
        
        try (Tracer.SpanInScope _ = micrometerTracer.withSpan(outboxSpan)) {
            Message<?> kafkaMessage = createTracingKafkaMessage(eventPayload, record, outboxSpan);                
            
            boolean sent = streamBridge.send(target.topic(), kafkaMessage);
            if (!sent) {
                throw new IllegalStateException("Failed to publish outbox record " + record.getId());
            }

            log.debug("Staging delete for outbox id={}", record.getId());
            outboxRepo.delete(record);
            
            log.info("Published and deleted outbox record id={} | TraceID: {}", 
            			record.getId(), record.getTraceId());
            
        } catch (Exception e) {
            outboxSpan.error(e);
            throw e; 
        } finally {
            outboxSpan.end();
        }            
    }
    
    /// Determines the Kafka topic and span name based on the event type.
    private EventTarget resolveEventTarget(RestaurantEvent eventPayload) {
        return switch (eventPayload) {
        
            case RestaurantApprovedEvent _ -> 
                new EventTarget(TOPIC_RESTAURANT_APPROVED, SPAN_RESTAURANT_APPROVED);
                
            case RestaurantRejectedEvent _ -> 
                new EventTarget(TOPIC_RESTAURANT_REJECTED, SPAN_RESTAURANT_REJECTED);
                
            case null, default -> 
                throw new IllegalArgumentException("Unsupported outbox event type: " 
                        	+ (eventPayload != null ? eventPayload.getClass().getName() : "null"));
        };
    }
    
    /// Converts the JSON payload of the outbox record into its corresponding event class.
    public RestaurantEvent convertToEvent(OutboxRecord record) {
        try {
            return switch (record.getEventType()) {
            
                case "RestaurantApproved" -> 
                		objectMapper.readValue(record.getPayload(), RestaurantApprovedEvent.class);
                		
                case "RestaurantRejected" -> 
                		objectMapper.readValue(record.getPayload(), RestaurantRejectedEvent.class);
                		
                default -> {
                    log.warn("Unknown eventType for outbox id={} eventType={}", 
                    			record.getId(), record.getEventType());
                    throw new IllegalArgumentException("Unknown target event type: " + record.getEventType());
                }
            };
        } catch (Exception e) {
            log.error("Failed to deserialize outbox payload for record id={} eventType={}", 
            			record.getId(), record.getEventType(), e);
            throw new RuntimeException("Failed to deserialize outbox payload for record ID: " + record.getId(), e);
        }
    }
    
    /// Creates a Kafka message with the appropriate tracing headers for observability.
    private Message<?> createTracingKafkaMessage(Object payload, OutboxRecord record, Span span) {
    	
        String w3cTraceParentHeader = String.format(W3C_TRACEPARENT_FORMAT, 
                span.context().traceId(), 
                span.context().spanId());

        return MessageBuilder.withPayload(payload)
                .setHeader(HEADER_TRACEPARENT, w3cTraceParentHeader) 
                .setHeader(KafkaHeaders.KEY, record.getAggregateId().getBytes(StandardCharsets.UTF_8))
                .build();
    }
    
    /// Builds a Micrometer TraceContext from the provided traceId and spanId.
    private TraceContext buildTraceContext(String traceId, String spanId) {
    	
        // Safe fallbacks to prevent runtime errors if trace tracking keys are empty
        if (traceId == null || spanId == null || traceId.isEmpty() || spanId.isEmpty()) {
            return null;
        }
        return micrometerTracer.traceContextBuilder()
                .traceId(traceId)
                .spanId(spanId)
                .sampled(true)
                .build();
    }	    
	
}
