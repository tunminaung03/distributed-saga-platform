// language: java
package dev.tunmin.saga.paymentservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.tunmin.saga.paymentservice.domain.OutboxRecord;
import dev.tunmin.saga.paymentservice.domain.Payment;
import dev.tunmin.saga.paymentservice.domain.PaymentStatus;
import dev.tunmin.saga.paymentservice.dto.OrderCreatedEvent;
import dev.tunmin.saga.paymentservice.dto.OrderStatus;
import dev.tunmin.saga.paymentservice.dto.RestaurantRejectedEvent;
import dev.tunmin.saga.paymentservice.repository.OutboxRepository;
import dev.tunmin.saga.paymentservice.repository.PaymentRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        // no-op; MockitoExtension handles init
    }

    @Test
    void processPaymentSaga_shouldSavePayment_andOutbox_whenPending() throws Exception {
        String orderId = "o-100";
        OrderCreatedEvent ev = new OrderCreatedEvent(orderId, "cust-1", "ITEM", 2, 12.5, OrderStatus.PENDING);

        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"dummy\":true}");
        when(outboxRepository.save(any(OutboxRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        paymentService.processPaymentSaga(ev);

        ArgumentCaptor<Payment> payCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(payCaptor.capture());
        Payment savedPayment = payCaptor.getValue();
        assertThat(savedPayment.getOrderId()).isEqualTo(orderId);
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.APPROVED);

        verify(objectMapper).writeValueAsString(any());
        ArgumentCaptor<OutboxRecord> outCaptor = ArgumentCaptor.forClass(OutboxRecord.class);
        verify(outboxRepository).save(outCaptor.capture());
        OutboxRecord savedOutbox = outCaptor.getValue();
        assertThat(savedOutbox.getAggregateId()).isEqualTo(orderId);
        assertThat(savedOutbox.getEventType()).isEqualTo("PaymentProcessed");
        assertThat(savedOutbox.getCreatedTime()).isNotNull();
    }

    @Test
    void processPaymentSaga_shouldBeIdempotent_whenPaymentExists() {
        String orderId = "o-200";
        OrderCreatedEvent ev = new OrderCreatedEvent(orderId, "cust-1", "ITEM", 1, 5.0, OrderStatus.PENDING);

        Payment existing = Payment.builder()
                .id(UUID.randomUUID().toString())
                .orderId(orderId)
                .amount(5.0)
                .status(PaymentStatus.APPROVED)
                .build();

        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        paymentService.processPaymentSaga(ev);

        verify(paymentRepository, never()).save(any(Payment.class));
        verify(outboxRepository, never()).save(any(OutboxRecord.class));
        verify(objectMapper, never()).writeValueAsString(any());
    }

    @Test
    void processPaymentSaga_shouldThrowAndNotSaveOutbox_whenSerializationFails() throws Exception {
        String orderId = "o-300";
        OrderCreatedEvent ev = new OrderCreatedEvent(orderId, "cust-1", "ITEM", 1, 7.0, OrderStatus.PENDING);

        when(paymentRepository.findByOrderId(orderId))
        	.thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class)))
        	.thenAnswer(inv -> inv.getArgument(0));
        
        JacksonException mockJacksonException = mock(JacksonException.class);        
        when(objectMapper.writeValueAsString(any()))
        	.thenThrow(mockJacksonException);

        assertThrows(RuntimeException.class, () -> paymentService.processPaymentSaga(ev));

        // payment was attempted to be saved before serialization failed
        verify(paymentRepository).save(any(Payment.class));
        // but outbox save should not be invoked
        verify(outboxRepository, never()).save(any(OutboxRecord.class));
    }

    @Test
    void handleOrderCompensation_shouldRefundExistingApprovedPayment() {
        String orderId = "o-400";
        Payment existing = Payment.builder()
                .id("pay-400")
                .orderId(orderId)
                .amount(20.0)
                .status(PaymentStatus.APPROVED)
                .build();

        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        RestaurantRejectedEvent rej = new RestaurantRejectedEvent(orderId, "no-stock", "dummy-reason");

        paymentService.handleOrderCompensation(rej);

        ArgumentCaptor<Payment> cap = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(cap.capture());
        Payment updated = cap.getValue();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }
}