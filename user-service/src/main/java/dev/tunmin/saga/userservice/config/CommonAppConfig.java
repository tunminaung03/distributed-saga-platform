package dev.tunmin.saga.userservice.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.grpc.BindableService;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class CommonAppConfig {
		
	@Bean
	ApplicationRunner grpcDebug(ApplicationContext context) {
	    
	    return _ -> {

	    	log.info("==== GRPC SERVICES INITIALIZED ====");
	        context.getBeansOfType(BindableService.class)
	                .forEach((name, bean) -> 
	                	log.info(" -> Bound target: {} [{}]", 
	                				name, bean.getClass().getSimpleName()));
	        log.info("===================================");

	    };
	}
}
