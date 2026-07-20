package dev.tunmin.saga.orderservice.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.tunmin.saga.orderservice.domain.Order;
import dev.tunmin.saga.orderservice.dto.CreateOrderRequest;

import dev.tunmin.saga.orderservice.service.OrderService;
import tools.jackson.databind.ObjectMapper;

/**
 * REST API layer slice tests for the Order Controller.
 * 
 * These tests leverage @WebMvcTest to spin up an isolated WebMVC context, 
 * explicitly verifying HTTP request routing, Jackson 3 payload serialization, 
 * and JSR-380 bean validation rules (@NotBlank, @Positive) before requests 
 * hit the underlying services.
 */
@WebMvcTest(OrderController.class)
public class OrderControllerWebTest {
	
	@Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;
    
    /**
     * Happy Path Scenario: Verifies that passing a valid token header (X-Perimeter-User-Id)
     * and a structured payload correctly routes to the controller, invokes the decoupled
     * service logic with the extracted header ID, and returns HTTP 200 OK.
     */
    @Test
    void createOrder_ShouldReturnOk_WhenServiceSucceeds() throws Exception {

    	String mockUserId = "1";
        Order mockOrder = Order.builder()
                .id("ORD_991")
                .customerId(mockUserId)
                .totalAmount(25.50)
                .build();

        // Service layer accepts the user ID extracted from the perimeter header boundary
        when(orderService.createOrder(
        			eq(mockUserId), 
        			any(CreateOrderRequest.class)))
                .thenReturn(mockOrder);

        mockMvc.perform(post("/orders")
                .header("X-Perimeter-User-Id", mockUserId) // Injected securely by the gateway
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new CreateOrderRequest(mockUserId, 25.50, "BURGER_01", 2)))) // CustomerId removed from body
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ORD_991"))
                .andExpect(jsonPath("$.customerId").value("1"));
    }
    
    /**
     * Edge Security Scenario: Verifies that if an unauthenticated request somehow evades the gateway
     * perimeter and lacks the X-Perimeter-User-Id header, the controller rejects the request
     * with an HTTP 400 Bad Request error (missing required servlet header constraint).
     */
    @Test
    void createOrder_ShouldReturnBadRequest_WhenPerimeterHeaderIsMissing() throws Exception {
        mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new CreateOrderRequest("1", 25.50, "BURGER_01", 2))))
                .andExpect(status().isBadRequest()); // Short-circuits safely due to @RequestHeader(required = true)
    }
    
    /**
     * Validation Payload Constraint Scenario: Verifies that passing invalid numeric structural values 
     * (e.g., negative or zero quantity) inside the JSON payload triggers your local 
     * MethodArgumentNotValidException within your GlobalExceptionHandler, returning an HTTP 400.
     */
    @Test
    void createOrder_ShouldReturnBadRequest_WhenPayloadFailsValidationConstraints() throws Exception {
        mockMvc.perform(post("/orders")
                .header("X-Perimeter-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new CreateOrderRequest("1", -10.00, "", 0)))) // Violates minimum validation limits
                .andExpect(status().isBadRequest());
    }
}
