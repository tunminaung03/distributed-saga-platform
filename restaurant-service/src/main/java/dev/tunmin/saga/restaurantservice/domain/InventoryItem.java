package dev.tunmin.saga.restaurantservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "restaurant_inventory")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryItem {
	
	@Id
	@Column(name = "item_code", length = 50)
    private String itemCode;
	
	@Column(name = "stock_count", nullable = false)
    private int stockCount;
}
