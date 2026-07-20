package dev.tunmin.saga.paymentservice.config;


import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import dev.tunmin.saga.paymentservice.dto.OrderCreatedEvent;
import dev.tunmin.saga.paymentservice.dto.RestaurantRejectedEvent;
import dev.tunmin.saga.paymentservice.service.PaymentService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class PaymentConsumerConfig {
	
	private final OpenTelemetry openTelemetry;
	private final String tracerScopeName;
	
	public PaymentConsumerConfig(
	        OpenTelemetry openTelemetry,
	        @Value("${app.telemetry.tracer-name:saga-orchestration-mesh}") String tracerScopeName) {
	    this.openTelemetry = openTelemetry;
	    this.tracerScopeName = tracerScopeName;	    
	}	
	
	/**
	 * Defines the main Saga handler for processing payment when a new order is created. 
	 * The PaymentService.processPaymentSaga method will handle the core logic of deducting funds and writing outbox records.
	 * 
	 * The returned Consumer<OrderCreatedEvent> is automatically registered as a Kafka listener by Spring Cloud Stream
	 * 
	 */
	@Bean
    Consumer<Message<OrderCreatedEvent>> processPayment(PaymentService paymentService) {		
		
        return message -> executeInActiveContext(        					
        					"consume_order_created_event", 
        					() -> paymentService.processPaymentSaga(message.getPayload())
        		);
    }
    
    @Bean
    Consumer<Message<RestaurantRejectedEvent>> compensatePayment(PaymentService paymentService) {
        return message -> executeInActiveContext(        					
        					"consume_restaurant_rejected_event", 
        					() -> paymentService.handleOrderCompensation(message.getPayload())
        );
    }

    private void executeInActiveContext(String spanName, Runnable businessLogic) {
        
    	log.info("executeInActiveContext(), spanName:{}", spanName);
    	
    	Tracer tracer = openTelemetry.getTracer(tracerScopeName);
        
        // Leverages native Context.current() established by enable-observation: true
        Span consumerSpan = tracer.spanBuilder(spanName)
                .setParent(Context.current())
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();

        try (Scope scope = consumerSpan.makeCurrent()) {
            businessLogic.run();
        } catch (Exception e) {
            consumerSpan.recordException(e);
            throw e; 
        } finally {
            consumerSpan.end();
        }
    }
}
