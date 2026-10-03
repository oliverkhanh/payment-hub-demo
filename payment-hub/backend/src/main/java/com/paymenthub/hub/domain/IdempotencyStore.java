package com.paymenthub.hub.domain;

import com.paymenthub.hub.infra.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Ghi/đọc sổ idempotency. BEAN RIÊNG có chủ đích — bài học Increment 2:
 *
 *   @Transactional chỉ có hiệu lực khi gọi QUA PROXY, tức từ BEAN KHÁC.
 *   this.method() đi "cửa sau", annotation bị bỏ qua.
 *
 * Mỗi method ở đây dùng REQUIRES_NEW: transaction của sổ phải ĐỘC LẬP với transaction
 * nghiệp vụ. Lý do: khi INSERT đụng UNIQUE, PostgreSQL ABORT cả transaction đó (SQLSTATE
 * 25P02) ⇒ nếu dùng chung tx thì cú SELECT để đọc lại bản ghi của thằng thắng cũng chết.
 */
@Service
public class IdempotencyStore {

    private final IdempotencyRecordRepository records;

    public IdempotencyStore(IdempotencyRecordRepository records) {
        this.records = records;
    }

    /**
     * Giành chỗ: INSERT TRƯỚC, không SELECT trước.
     *
     * Nếu SELECT trước thì hai request đồng thời đều thấy "chưa có" ⇒ cả hai cùng tạo
     * payment. Chỉ UNIQUE ở DB mới phân định được ai thắng.
     *
     * ⚠️ CỐ Ý KHÔNG try/catch ở ĐÂY. Bài học Increment 2, lần này ở dạng khác:
     * nếu bắt DataIntegrityViolationException ngay trong method này rồi return bình thường,
     * Spring sẽ đi COMMIT cái transaction REQUIRES_NEW — mà PostgreSQL đã ABORT nó sau lệnh
     * INSERT lỗi (SQLSTATE 25P02) ⇒ commit nổ ⇒ 500.
     * Phải để exception BAY RA (tx rollback gọn), rồi bắt Ở NGOÀI ranh giới transaction.
     *
     * @throws DataIntegrityViolationException nếu khoá đã tồn tại (THUA cuộc đua).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord claim(Long merchantId, String key, String endpoint,
                                   String fingerprint) {
        // saveAndFlush: ép INSERT chạm DB NGAY (save() có thể hoãn tới lúc commit).
        return records.saveAndFlush(new IdempotencyRecord(merchantId, key, endpoint, fingerprint));
    }

    /** Đọc lại bản ghi của thằng thắng — chạy trong transaction SẠCH, không phải tx đã abort. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(Long merchantId, String key) {
        return records.findByMerchantIdAndIdempotencyKey(merchantId, key);
    }

    /** Đóng dấu kết quả: lưu NGUYÊN VĂN response đã phát ra. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long recordId, int status, String responseBody, Long paymentId) {
        records.findById(recordId).ifPresent(r -> r.complete(status, responseBody, paymentId));
    }

    /**
     * Nghiệp vụ thất bại ⇒ XOÁ chỗ đã giành, để merchant sửa request rồi gửi lại
     * cùng khoá được. Không xoá thì khoá bị "kẹt" ở IN_PROGRESS suốt 24h.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long recordId) {
        records.deleteById(recordId);
    }
}
