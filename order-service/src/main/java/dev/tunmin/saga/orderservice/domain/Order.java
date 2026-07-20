package dev.tunmin.saga.orderservice.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customer_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {
	
	@Id
    private String id;
    private String customerId;
    private double totalAmount;
    
    private String itemCode;  // Added to persist what they ordered
    private int quantity;
    
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
}
