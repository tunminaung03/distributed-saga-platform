package dev.tunmin.saga.paymentservice.config.observability;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;

@Configuration
public class TracingConfig {
	/**
	 * Installs the OpenTelemetry Logback Appender during application startup.
	 *
	 * The appender exports Logback log records through the application's
	 * OpenTelemetry SDK, allowing logs to be correlated with the current
	 * trace and span context when available.
	 *
	 * @param openTelemetry the application's OpenTelemetry instance
	 * @return a {@link CommandLineRunner} that installs the OpenTelemetry
	 *         Logback Appender after the application context has been initialized
	 */
	@Bean
    CommandLineRunner installOpenTelemetryAppender(
    							OpenTelemetry openTelemetry) {
		
        return _ -> OpenTelemetryAppender.install(openTelemetry);
    }
}

