package dev.tunmin.saga.restaurantservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.tunmin.saga.restaurantservice.domain.InventoryStatus;
import dev.tunmin.saga.restaurantservice.domain.OutboxRecord;
import dev.tunmin.saga.restaurantservice.domain.RestaurantTicket;
import dev.tunmin.saga.restaurantservice.domain.RestaurantTicketStatus;
import dev.tunmin.saga.restaurantservice.dto.PaymentProcessedEvent;
import dev.tunmin.saga.restaurantservice.dto.PaymentStatus;
import dev.tunmin.saga.restaurantservice.repository.OutboxRepository;
import dev.tunmin.saga.restaurantservice.repository.RestaurantTicketRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit test suite for the RestaurantService business orchestration layer.
 * 
 * These tests leverage Mockito to isolate the core Saga transaction step transitions. 
 * They validate that tickets are processed based on downstream stock availability and 
 * that the uniform transactional outbox pipeline is consistently updated.
 */
@ExtendWith(MockitoExtension.class) 
class RestaurantServiceTest {

	@Mock
	RestaurantTicketRepository ticketRepository;
	
	@Mock
	RestaurantInventoryService inventoryService;
	
	@Mock
	ObjectMapper objectMapper;
	
	@Mock
	OutboxRepository outboxRepository;
	
	@InjectMocks
	RestaurantService restaurantService;		
	
	/**
     * Verifies that the service short-circuits early when receiving a duplicate order event.
     * It must not modify the inventory or write duplicate entries to the outbox database table.
     */
	@Test
	void processRestaurantStep_shouldShortCircuit_whenTicketExists() {
	    String orderId = "ord-1";
	    PaymentProcessedEvent ev = new PaymentProcessedEvent(
	    			orderId, "cust-1", "BURGER_01", 1, 10.0, PaymentStatus.APPROVED);
	
	    when(ticketRepository.existsByOrderId(orderId))
	    	.thenReturn(true);
	
	    restaurantService.processRestaurantStep(ev);
	
	    verify(ticketRepository).existsByOrderId(orderId);
	    verifyNoInteractions(inventoryService);
	    verify(outboxRepository, never()).save(any());
	}
	
	/**
     * Happy path scenario: When inventory is successfully allocated for an order, 
     * the system transitions the ticket state to PREPARING and issues a 'RestaurantApproved' outbox event.
     */
	@Test
	void processRestaurantStep_shouldCreatePreparingTicket_andSaveApprovedOutbox_whenInventoryAllocated() throws Exception {
	    String orderId = "ord-2";
	    PaymentProcessedEvent ev = new PaymentProcessedEvent(
	    			orderId, "cust-2", "PIZZA_01", 2, 20.0, PaymentStatus.APPROVED);
	
	    when(ticketRepository.existsByOrderId(orderId))
	    	.thenReturn(false);
	    when(inventoryService.verifyAndDeductStock(ev.itemCode(), ev.quantity()))
	    	.thenReturn(InventoryStatus.ALLOCATED);
	
	    // Capture and mirror back the entity passed to ticketRepository.save()
	    ArgumentCaptor<RestaurantTicket> ticketCaptor = ArgumentCaptor.forClass(RestaurantTicket.class);
	    when(ticketRepository.save(ticketCaptor.capture()))
	    	.thenAnswer(inv -> inv.getArgument(0));
	
	    when(objectMapper.writeValueAsString(any()))
	    	.thenReturn("{\"approved\":true}");
	    ArgumentCaptor<OutboxRecord> outCaptor = ArgumentCaptor.forClass(OutboxRecord.class);
	    when(outboxRepository.save(outCaptor.capture()))
	    	.thenAnswer(inv -> inv.getArgument(0));
	    
	    restaurantService.processRestaurantStep(ev);
	    
	    // Assert: Validate ticket changes to PREPARING
	    RestaurantTicket savedTicket = ticketCaptor.getValue();
	    assertEquals(orderId, savedTicket.getOrderId());
	    assertEquals(RestaurantTicketStatus.PREPARING, savedTicket.getStatus());
	    
	    // Assert: Validate outbox registration properties
	    OutboxRecord savedOut = outCaptor.getValue();
	    assertEquals(orderId, savedOut.getAggregateId());
	    assertEquals("RestaurantApproved", savedOut.getEventType());
	    assertNotNull(savedOut.getPayload());
	}
	
	/**
     * Negative scenario path: When stock is insufficient, the transaction fails gracefully, 
     * transitioning the ticket to REJECTED and writing a 'RestaurantRejected' rollback trigger to the outbox.
     */
	@Test
	void processRestaurantStep_shouldCreateRejectedTicket_andSaveRejectedOutbox_whenInsufficientStock() throws Exception {
	    String orderId = "ord-3";
	    PaymentProcessedEvent ev = new PaymentProcessedEvent(
	    		orderId, "cust-3", "RAMEN_02", 5, 50.0, PaymentStatus.APPROVED);
	
	    when(ticketRepository.existsByOrderId(orderId))
	    	.thenReturn(false);
	    when(inventoryService.verifyAndDeductStock(ev.itemCode(), ev.quantity()))
	    	.thenReturn(InventoryStatus.INSUFFICIENT_STOCK);
	
	    ArgumentCaptor<RestaurantTicket> ticketCaptor = ArgumentCaptor.forClass(RestaurantTicket.class);
	    when(ticketRepository.save(ticketCaptor.capture()))
	    	.thenAnswer(inv -> inv.getArgument(0));
	
	    when(objectMapper.writeValueAsString(any()))
	    	.thenReturn("{\"rejected\":true}");
	    ArgumentCaptor<OutboxRecord> outCaptor = ArgumentCaptor.forClass(OutboxRecord.class);
	    when(outboxRepository.save(outCaptor.capture()))
	    	.thenAnswer(inv -> inv.getArgument(0));
	
	    restaurantService.processRestaurantStep(ev);
	
	    RestaurantTicket savedTicket = ticketCaptor.getValue();
	    assertEquals(orderId, savedTicket.getOrderId());
	    assertEquals(RestaurantTicketStatus.REJECTED, savedTicket.getStatus());
	
	    OutboxRecord savedOut = outCaptor.getValue();
	    assertEquals(orderId, savedOut.getAggregateId());
	    assertEquals("RestaurantRejected", savedOut.getEventType());
	    assertNotNull(savedOut.getPayload());
	}
	
	/**
     * Input validation path: Verifies that the restaurant processing block rejects incoming 
     * upstream event statuses that are not explicitly marked as APPROVED.
     */
	@Test
	void processRestaurantStep_shouldThrow_whenStatusNotApproved() {
	    String orderId = "ord-4";
	    PaymentProcessedEvent ev = new PaymentProcessedEvent(
	    			orderId, "cust-4", "ICE_CREAM_02", 1, 5.0, PaymentStatus.FAILED);
	
	    when(ticketRepository.existsByOrderId(orderId)).thenReturn(false);
	
	    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, 
	    								() -> restaurantService.processRestaurantStep(ev));
	    assertTrue(ex.getMessage().contains("Expected APPROVED"));
	    verify(ticketRepository).existsByOrderId(orderId);
	    verifyNoInteractions(outboxRepository);
	}

}
