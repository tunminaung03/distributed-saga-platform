package dev.tunmin.saga.restaurantservice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import dev.tunmin.saga.restaurantservice.domain.OutboxRecord;

@DataJpaTest
class OutboxRepositoryTest {

    @Autowired
    private OutboxRepository outboxRepository;
    
    @Test
    void findNextAvailableBatch_returnsEmpty_whenNoRecords() {
        List<OutboxRecord> batch = outboxRepository.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10));
        assertNotNull(batch);
        assertTrue(batch.isEmpty(), "Expected no outbox records");
    }

    @Test
    void findNextAvailableBatch_returnsAtMostTen_recordsOrderedByCreatedTime() {
        
    	LocalDateTime base = LocalDateTime.now().minusSeconds(120);
        List<OutboxRecord> inserted = new ArrayList<>();

        // Replaced functional stream with an imperative loop matching guidelines
        for (int i = 0; i < 12; i++) {
            OutboxRecord record = OutboxRecord.builder()
                    .aggregateId("order-" + i)
                    .eventType("TestEvent")
                    .payload("{\"i\":" + i + "}")
                    .createdTime(base.plusSeconds(i))
                    .build();
                    
            inserted.add(outboxRepository.save(record));
        }

        List<OutboxRecord> batch = outboxRepository.findByOrderByCreatedTimeAsc(PageRequest.of(0, 10));
        

        // should return non-null, at most 10 items
        assertNotNull(batch);
        assertTrue(batch.size() <= 10, "Expected at most 10 records returned by batch query");

        // verify chronological order (ascending createdTime)
        List<LocalDateTime> times = batch.stream()
                .map(OutboxRecord::getCreatedTime)
                .collect(Collectors.toList());

        List<LocalDateTime> sorted = times.stream()
        			.sorted()
        			.collect(Collectors.toList());
        assertEquals(sorted, times, "Returned batch must be ordered by createdTime ascending");

        // verify returned records are the oldest ones that were inserted
        List<String> returnedAggregateIds = batch.stream()
                .map(OutboxRecord::getAggregateId)
                .collect(Collectors.toList());

        List<String> expectedOldest = inserted.stream()
                .sorted((a, b) -> a.getCreatedTime().compareTo(b.getCreatedTime()))
                .limit(10)
                .map(OutboxRecord::getAggregateId)
                .collect(Collectors.toList());

        assertEquals(expectedOldest, returnedAggregateIds);
    }
}
