package dev.tunmin.saga.restaurantservice.service;

import org.springframework.stereotype.Service;

import dev.tunmin.saga.restaurantservice.domain.InventoryStatus;
import dev.tunmin.saga.restaurantservice.repository.InventoryItemRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class RestaurantInventoryService {
	
	private final InventoryItemRepository inventoryRepository;

    @Transactional
    public InventoryStatus verifyAndDeductStock(String itemCode, int quantity) {
    	
        return inventoryRepository.findById(itemCode)
                .map(item -> {
                    if (item.getStockCount() >= quantity) {
                        item.setStockCount(item.getStockCount() - quantity);
                        
                        log.info("Deducted inventory itemm with code: {}, for quantity: {}", quantity);
                        
                        inventoryRepository.save(item);
                        return InventoryStatus.ALLOCATED; 
                    }
                    log.info("Insufficient inventory itemm with code: {}, for quantity: {}", quantity);
                    return InventoryStatus.INSUFFICIENT_STOCK;
                })
                // If the item doesn't exist
                .orElse(InventoryStatus.ITEM_NOT_FOUND);
    }
}
