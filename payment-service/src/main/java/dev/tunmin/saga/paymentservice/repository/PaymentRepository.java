package dev.tunmin.saga.paymentservice.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import dev.tunmin.saga.paymentservice.domain.Payment;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, String> {    
    Optional<Payment> findByOrderId(String orderId);
}
