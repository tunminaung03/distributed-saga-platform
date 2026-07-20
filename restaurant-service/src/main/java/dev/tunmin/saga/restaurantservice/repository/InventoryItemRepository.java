package dev.tunmin.saga.restaurantservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import dev.tunmin.saga.restaurantservice.domain.InventoryItem;

@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, String> {

}
