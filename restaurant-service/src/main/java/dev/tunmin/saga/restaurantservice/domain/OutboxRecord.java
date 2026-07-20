package dev.tunmin.saga.restaurantservice.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "outbox_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxRecord {
	
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Lob // Uses TEXT or JSON column data mappings under the hood
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_time", nullable = false)
    private LocalDateTime createdTime;
    
    // --- ADDED: Context Propagation Tracking Fields ---
    @Column(name = "trace_id", nullable = false, length = 32)
    private String traceId;

    @Column(name = "span_id", nullable = false, length = 16)
    private String spanId;
}
