package dev.tunmin.saga.apigatewayservice.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import dev.tunmin.saga.apigatewayservice.dto.AuthRequest;
import dev.tunmin.saga.apigatewayservice.exception.GlobalExceptionHandler;
import dev.tunmin.saga.apigatewayservice.exception.InvalidCredentialsException;
import dev.tunmin.saga.apigatewayservice.infra.grpc.UserGrpcClient;
import dev.tunmin.saga.user.grpc.AuthResponse;

@WebFluxTest(
	controllers = {
		AuthenticationController.class, 
		GlobalExceptionHandler.class
	}
)
public class AuthenticationControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private UserGrpcClient userGrpcClient;

    /**
     * Golden Path: Verifies that passing a valid AuthRequest payload hits the endpoint,
     * routes via the virtual thread executor layer, and maps the gRPC response back to HTTP 200.
     */
    @Test
    void login_ShouldReturnOkAndToken_WhenCredentialsAreValid() {
        AuthResponse grpcMockResponse = AuthResponse.newBuilder()
                .setToken("mocked-valid-jwt-token-string")
                .setTokenType("Bearer")
                .setExpiresIn(3600)
                .build();

        when(userGrpcClient.authenticate(any(AuthRequest.class))).thenReturn(grpcMockResponse);

        webTestClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AuthRequest("developer@tunmin.dev", "securePassword123"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.token").isEqualTo("mocked-valid-jwt-token-string")
                .jsonPath("$.type").isEqualTo("Bearer")
                .jsonPath("$.expiresIn").isEqualTo(3600);
    }

    /**
     * Translated Exception Mapping Path: Verifies that when the internal gRPC execution 
     * layer signals an unauthenticated state, your advice advisor converts the error 
     * into a standardized HTTP 401 Unauthorized payload structure.
     */
    @Test
    void login_ShouldReturnUnauthorized_WhenInvalidCredentialsExceptionIsThrown() {
        when(userGrpcClient.authenticate(any(AuthRequest.class)))
                .thenThrow(new InvalidCredentialsException("Cryptographic credential check failed"));

        webTestClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AuthRequest("developer@tunmin.dev", "wrongPassword"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.message").isEqualTo("Cryptographic credential check failed");
    }

    /**
     * Payload Structural Constraints Path: Verifies that passing blank properties (violating @NotBlank) 
     * short-circuits execution before network outlays, returning an HTTP 400 Bad Request error structure.
     */
    @Test
    void login_ShouldReturnBadRequest_WhenPayloadInputFailsValidationRules() {
        webTestClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AuthRequest("", "")) // Blank input violations
                .exchange()
                .expectStatus().isBadRequest();
    }
}
