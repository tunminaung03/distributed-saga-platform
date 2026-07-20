package dev.tunmin.saga.apigatewayservice.config.observability;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.util.StringUtils;

import io.micrometer.observation.ObservationPredicate;

/**
 * Central configuration for Micrometer Observation filtering.
 *
 * This class defines a unified {@link ObservationPredicate} that suppresses
 * unwanted spans to keep telemetry clean and focused:
 *   - Scheduled tasks listed in application.yaml (observability.ignored.tasks)
 *   - Probe endpoints such as /actuator/health and /actuator/info
 *
 * Values are externalized in application.yaml for flexibility and minimal image size.
 */
@Configuration
public class ObservationConfig {
	
    private final Set<String> ignoredPaths;
    private final Set<String> ignoredTasks;
    
    // properties are optional, default to empty string if not provided
    public ObservationConfig(
    		@Value("${app.observability.ignored.paths:}") String paths,
            @Value("${app.observability.ignored.tasks:}") String tasks) {
    	
    	this.ignoredPaths = StringUtils.hasLength(paths)    				
                				? Stream.of(paths.split(","))
                						.map(String::trim)
                						.collect(Collectors.toSet())
    							: Collections.emptySet();
    	this.ignoredTasks = StringUtils.hasLength(tasks)    				
								? Stream.of(tasks.split(","))
										.map(String::trim)
										.collect(Collectors.toSet())
								: Collections.emptySet();;
	}
	
	/**
	 * Configures an ObservationPredicate to filter out specific scheduled task and paths metrics
	 *
	 * This predicate checks if the observation context is an instance of ScheduledTaskObservationContext
	 * and if the observation name matches "tasks.scheduled.execution". If both conditions are met and the
	 * method name is in the IGNORED_TASKS set, the observation will be ignored.
	 *
	 * @return an ObservationPredicate that filters out specific scheduled task metrics
	 */
	@Bean
	ObservationPredicate observationPredicate() {
		
		// Lambda expression - Predicate to filter out specific scheduled task metrics
	    return (name, context) -> {
	    	
	    	// 1. Filter scheduled tasks you want to ignore
            if (context instanceof ScheduledTaskObservationContext taskContext
                    && "tasks.scheduled.execution".equals(name)
                    && ignoredTasks.contains(taskContext.getMethod().getName())) {
                return false;
            }

            // 2. Filter actuator probe endpoints
            /// IMPORTANT: for reactive app, use `org.springframework.http.server.reactive.observation.ServerRequestObservationContext`
            if (context instanceof ServerRequestObservationContext serverContext) {

            	// different usage from servlet-based `org.springframework.web.servlet.observation.ServerRequestObservationContext`
            	String path = serverContext.getCarrier().getURI().getPath();
                if (path != null 
                		&& ignoredPaths.stream().anyMatch(path::contains)) {
                    return false;
                }
            }

            // Allow everything else
            return true;
	    };
	}
}
