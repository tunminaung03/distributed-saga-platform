package dev.tunmin.saga.restaurantservice.config.logging;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

public class MessageContainsFilter extends Filter<ILoggingEvent>{
	
	private List<String> keywords;
	
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

        for (String keyword : keywords) {
            if (lowerMsg.contains(keyword)) {
                return FilterReply.DENY;
            }
        }

        return FilterReply.NEUTRAL;
	}

}
