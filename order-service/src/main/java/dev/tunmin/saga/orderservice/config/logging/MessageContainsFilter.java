package dev.tunmin.saga.orderservice.config.logging;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * A Logback filter that denies logging events containing specified keywords in their messages.
 *
 * This filter can be configured with a comma-separated list of keywords. If a logging event's
 * message contains any of the specified keywords (case-insensitive), the event will be denied
 * and not logged. Otherwise, the event will be allowed to pass through.
 * 
 * See logback-spring.xml for configuration example.
 */
public class MessageContainsFilter extends Filter<ILoggingEvent>{
	
	private List<String> keywords;
	
	/// Sets the keywords to filter out from logging events.
	public void setKeywords(String keywords) {
        this.keywords = Arrays.stream(keywords.split(","))
                				.map(String::trim)
                				.filter(s -> !s.isBlank())
                				.map(String::toLowerCase)
                				.collect(Collectors.toList());
    }
	
	@Override
    public void start() {
		
        if (keywords == null || keywords.isEmpty()) {
        	
        	// used method in ContextAwareBase to report back
        	addError("keyword must be configured");
        }
        super.start();
    }
	
	/// The decide method determines whether a logging event should be logged or 
	/// denied based on the presence of specified keywords in the event's message.
	/// - **NEUTRAL**: The filter does not make a decision, and the logging event will be processed by other filters or appenders.
	/// - **DENY**: The logging event will be denied and not logged.
	@Override
	public FilterReply decide(ILoggingEvent event) {
		
		if (!isStarted() || keywords == null || keywords.isEmpty()) {
            return FilterReply.NEUTRAL;
        }

        String msg = event.getFormattedMessage();
        if (msg == null) {
            return FilterReply.NEUTRAL;
        }

        String lowerMsg = msg.toLowerCase();
        // Check if the message contains any of the keywords
        for (String keyword : keywords) {
            if (lowerMsg.contains(keyword)) {
                return FilterReply.DENY;
            }
        }

        return FilterReply.NEUTRAL;
	}

}
