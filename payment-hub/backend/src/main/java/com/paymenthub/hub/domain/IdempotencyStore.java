package com.paymenthub.hub.domain;

import com.paymenthub.hub.infra.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class IdempotencyStore {
    private final IdempotencyRecordRepository records;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord claim(Long merchantId, String key, String endpoint,
                                   String fingerprint) {
        return records.saveAndFlush(new IdempotencyRecord(merchantId, key, endpoint, fingerprint));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(Long merchantId, String key) {
        return records.findByMerchantIdAndIdempotencyKey(merchantId, key);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long recordId, int status, String responseBody, Long paymentId) {
        records.findById(recordId).ifPresent(r -> r.complete(status, responseBody, paymentId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long recordId) {
        records.deleteById(recordId);
    }
}
