package com.paymenthub.bank.infra;

import com.paymenthub.bank.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            SELECT * FROM outbox
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendings(@Param("now") Instant now,
                                   @Param("batchSize") int batchSize);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox
            SET status = 'SENT', sent_at = :now
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markSent(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox
            SET next_attempt_at = :nextAttemptAt, last_error = :lastError
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int scheduleRetry(@Param("id") Long id,
                      @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("lastError") String lastError);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox
            SET status = 'FAILED', last_error = :lastError
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);
}
