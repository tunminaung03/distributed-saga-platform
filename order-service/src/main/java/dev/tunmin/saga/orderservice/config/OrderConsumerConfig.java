package dev.tunmin.saga.orderservice.config;

import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import dev.tunmin.saga.orderservice.dto.RestaurantApprovedEvent;
import dev.tunmin.saga.orderservice.dto.RestaurantRejectedEvent;
import dev.tunmin.saga.orderservice.service.OrderService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;

/**
 * OrderConsumerConfig is a Spring configuration class that defines consumer beans for handling
 * restaurant approval and rejection events in a saga orchestration context. It integrates with
 * OpenTelemetry to provide distributed tracing for the event consumption process.
 */
@Configuration
@Slf4j
public class OrderConsumerConfig {		
	
	private final OpenTelemetry openTelemetry;
	private final String tracerScopeName;
	
	public OrderConsumerConfig(
	        OpenTelemetry openTelemetry,
	        @Value("${app.telemetry.tracer-name:saga-orchestration-mesh}") String tracerScopeName) {
	    this.openTelemetry = openTelemetry;
	    this.tracerScopeName = tracerScopeName;	    
	}
	
	/// Spring Cloud Stream Consumer Bean for RestaurantApprovedEvent
	@Bean
    Consumer<Message<RestaurantApprovedEvent>> restaurantApproved(
    		OrderService orderService) {
		
		return message -> executeInActiveContext(							
							"consume_restaurant_approved_event", 
							() -> orderService.confirmOrder(message.getPayload())
				);			
    }
	
	/// Spring Cloud Stream Consumer Bean for RestaurantRejectedEvent
	@Bean
    Consumer<Message<RestaurantRejectedEvent>> restaurantRejected(
    		OrderService orderService) {
		
		return message -> executeInActiveContext(							
							"consume_restaurant_rejected_event", 
							() -> orderService.cancelOrder(message.getPayload())
				);				
    }		
    
    private void executeInActiveContext(String spanName, Runnable businessLogic) {
        
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
