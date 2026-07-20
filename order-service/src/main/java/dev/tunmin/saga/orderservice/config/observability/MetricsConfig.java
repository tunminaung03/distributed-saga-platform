package dev.tunmin.saga.orderservice.config.observability;

import org.springframework.cloud.stream.binder.kafka.KafkaBinderMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.config.MeterFilter;

@Configuration
public class MetricsConfig {
	
	/**
	 * Disables Spring Cloud Stream Kafka Binder offset lag metrics.
	 *
	 * These metrics periodically inspect Kafka consumer state and can trigger
	 * ConcurrentModificationException when used together with Kafka transactions
	 * (EOS) and Virtual Threads.
	 *
	 * This does not affect application tracing or business metrics.
	 */
	@Bean
    MeterFilter disableKafkaLagMetrics() {
		
        return MeterFilter.denyNameStartsWith(
        						KafkaBinderMetrics.OFFSET_LAG_METRIC_NAME);
    }

}
