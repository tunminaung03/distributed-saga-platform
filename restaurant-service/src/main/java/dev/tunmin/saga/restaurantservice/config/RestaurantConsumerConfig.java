package dev.tunmin.saga.restaurantservice.config;

import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import dev.tunmin.saga.restaurantservice.dto.PaymentProcessedEvent;
import dev.tunmin.saga.restaurantservice.service.RestaurantService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class RestaurantConsumerConfig {		

	private final OpenTelemetry openTelemetry;
	private final String tracerScopeName;
	
	public RestaurantConsumerConfig(
	        OpenTelemetry openTelemetry,
	        @Value("${app.telemetry.tracer-name:saga-orchestration-mesh}") String tracerScopeName) {
	    this.openTelemetry = openTelemetry;
	    this.tracerScopeName = tracerScopeName;	    
	}
		
	@Bean
	Consumer<Message<PaymentProcessedEvent>> restaurantDecision(
			RestaurantService restaurantService) {
		
		return message -> executeInActiveContext(							
							"consume_payment_processed_event", 
							() -> restaurantService.processRestaurantStep(message.getPayload())
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
