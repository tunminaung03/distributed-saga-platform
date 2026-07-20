package dev.tunmin.saga.apigatewayservice.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.net.HttpHeaders;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public class JwtPerimeterGuardGatewayFilterFactoryTest {

    private JwtPerimeterGuardGatewayFilterFactory filterFactory;
    private GatewayFilterChain filterChain;
    private ArgumentCaptor<ServerWebExchange> exchangeCaptor;

    private final String testSecret = "9f8c3b7e2d6a4f1c9b0e7d5a3c2f1e6b7a8c9d0e1f2a3b4c5d6e7f8091a2b3c4";
    private final String testIssuer = "saga-ecosystem-auth";

    @BeforeEach
    void setUp() {
        // Instantiate target guard using standard test properties
        this.filterFactory = new JwtPerimeterGuardGatewayFilterFactory(testSecret, testIssuer);
        this.filterChain = mock(GatewayFilterChain.class);
        this.exchangeCaptor = ArgumentCaptor.forClass(ServerWebExchange.class);
        
        // Mock chain handler to report a complete reactive state naturally
        when(filterChain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    /**
     * Golden Path: Verifies that passing a signed valid token lets the request proceed 
     * through the filter, mutates headers to include X-Perimeter-User-Id, and triggers chain completion.
     */
    @Test
    void apply_ShouldForwardAndMutateHeader_WhenTokenSignatureIsValid() {
        // Arrange: Mint a valid JWT using the matching shared test key configuration
        String validToken = JWT.create()
                .withIssuer(testIssuer)
                .withSubject("alex@example.com")
                .withClaim("user-id", "9942")
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + 60000))
                .sign(Algorithm.HMAC256(testSecret));

        MockServerHttpRequest request = MockServerHttpRequest.post("/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilter filter = filterFactory.apply(new JwtPerimeterGuardGatewayFilterFactory.Config());

        // Act & Assert
        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        // Verify that the request mutated successfully and forwarded the accurate identity downstream
        verify(filterChain).filter(exchangeCaptor.capture());
        ServerWebExchange mutatedExchange = exchangeCaptor.getValue();
        
        String extractedUserHeader = mutatedExchange.getRequest().getHeaders().getFirst("X-Perimeter-User-Id");
        assertNotNull(extractedUserHeader);
        assertEquals("9942", extractedUserHeader);
    }

    /**
     * Missing Credentials Path: Verifies that if a client completely omits the Authorization header, 
     * the filter short-circuits the chain loop early, returning an HTTP 401 Unauthorized status.
     */
    @Test
    void apply_ShouldReturnUnauthorized_WhenAuthorizationHeaderIsMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/orders").build(); // No header appended
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilter filter = filterFactory.apply(new JwtPerimeterGuardGatewayFilterFactory.Config());

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(filterChain); // Chain invocation block verification passed
    }

    /**
     * Tampered Token Scenario: Verifies that if a client supplies an invalid or tampered 
     * signature string signature verification throws, returning an HTTP 403 Forbidden intercept response.
     */
    @Test
    void apply_ShouldReturnForbidden_WhenTokenSignatureIsInvalidOrTampered() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.tampered.token.string")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilter filter = filterFactory.apply(new JwtPerimeterGuardGatewayFilterFactory.Config());

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verifyNoInteractions(filterChain);
    }
}
