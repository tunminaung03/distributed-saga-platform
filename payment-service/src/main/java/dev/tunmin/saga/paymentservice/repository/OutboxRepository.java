package dev.tunmin.saga.paymentservice.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import dev.tunmin.saga.paymentservice.domain.OutboxRecord;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface OutboxRepository 
			extends JpaRepository<OutboxRecord, UUID> {

@Lock(LockModeType.PESSIMISTIC_WRITE)
// The timeout hint value of -2 tells Hibernate to append "SKIP LOCKED". 
// Instead of failing or waiting, it silently skips rows currently locked by other instances.
@QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", 
			value = "-2")})
List<OutboxRecord> findByOrderByCreatedTimeAsc(Pageable pageable);

}
