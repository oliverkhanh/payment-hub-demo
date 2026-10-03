package com.paymenthub.bank.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.bank.infra.BankAccountRepository;
import com.paymenthub.bank.infra.BankOutboxRepository;
import com.paymenthub.bank.infra.BankTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Nghiệp vụ của ngân hàng. Thành phần 1 mới làm bước MỞ PHIÊN giao dịch;
 * trừ tiền + callback về Hub là thành phần 2.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BankTransactionRepository transactions;
    private final BankAccountRepository accounts;
    private final BankOutboxRepository outbox;
    private final ObjectMapper json;
    private final TransactionWriter writer;

    public TransactionService(BankTransactionRepository transactions,
                              BankAccountRepository accounts, BankOutboxRepository outbox,
                              ObjectMapper json, TransactionWriter writer) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.outbox = outbox;
        this.json = json;
        this.writer = writer;
    }

    public record CreateCommand(String bankCode, long amount, String currency, String merchantName,
                                String idempotencyKey, String callbackUrl, String returnUrl) { }

    public record CreateResult(BankTransaction transaction, boolean created) { }

    /**
     * Mở phiên giao dịch. IDEMPOTENT theo (bank_code, idempotency_key):
     * Hub retry cùng khoá ⇒ trả lại ĐÚNG giao dịch cũ, KHÔNG tạo giao dịch thứ hai.
     *
     * ⚠️ KHÔNG @Transactional ở đây — chỗ bắt DataIntegrityViolationException phải nằm
     * NGOÀI transaction, nếu không Spring sẽ đi commit một tx đã bị Postgres abort.
     * (Bài học Increment 3, xem docs/notes/spring-transactional-catch-boundary.md)
     */
    public CreateResult createOrGet(CreateCommand cmd) {
        // Lớp 1 — check-first: ca thường (Hub retry sau khi lần trước đã xong).
        Optional<BankTransaction> existing =
                transactions.findByBankCodeAndIdempotencyKey(cmd.bankCode(), cmd.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Giao dịch đã tồn tại cho khoá {} — trả lại bản cũ", cmd.idempotencyKey());
            return new CreateResult(existing.get(), false);
        }

        try {
            // Lớp 2 — UNIQUE(bank_code, idempotency_key) là chốt chặn atomic ở DB.
            return new CreateResult(writer.insert(newTransaction(cmd)), true);
        } catch (DataIntegrityViolationException race) {
            // Lớp 3 — thua cuộc đua: đọc lại bản ghi của thằng thắng, ở transaction SẠCH.
            return transactions.findByBankCodeAndIdempotencyKey(cmd.bankCode(), cmd.idempotencyKey())
                    .map(txn -> new CreateResult(txn, false))
                    .orElseThrow(() -> race);
        }
    }

    private BankTransaction newTransaction(CreateCommand cmd) {
        return new BankTransaction(
                "BNK-" + randomSuffix(),
                cmd.bankCode(), cmd.amount(), cmd.currency(), cmd.merchantName(),
                cmd.idempotencyKey(), cmd.callbackUrl(), cmd.returnUrl());
    }

    private static String randomSuffix() {
        return Long.toHexString(RANDOM.nextLong() & 0xFFFFFFFFFFL).toUpperCase();
    }

    @Transactional(readOnly = true)
    public Optional<BankTransaction> findForCheckout(String bankCode, String bankTxnId) {
        return transactions.findByBankTxnIdAndBankCode(bankTxnId, bankCode);
    }

    /** Ba kết cục của việc trừ tiền. */
    public sealed interface PayResult {
        record Paid(long amount, long newBalance) implements PayResult { }

        /**
         * ⭐ LẦN THỬ này hỏng — KHÔNG phải giao dịch hỏng.
         * Giao dịch vẫn PENDING để Ana nhập tài khoản khác. Cùng nguyên tắc với sai PIN:
         * đóng giao dịch vì một lần gõ nhầm là bắt khách quay về merchant đặt lại đơn.
         */
        record AttemptFailed(DeclineReason reason) implements PayResult { }

        /** Giao dịch đã được chốt từ trước (bấm hai lần, hai tab). KHÔNG trừ thêm lần nào. */
        record AlreadyFinished(BankTransaction.Status status) implements PayResult { }
    }

    /** Ném ra để BẮT CẢ TRANSACTION ROLLBACK khi thua cuộc đua ở CAS thứ hai. */
    public static class ConcurrentFinishException extends RuntimeException {
        public ConcurrentFinishException() { super("Giao dịch vừa được chốt bởi luồng khác"); }
    }

    /**
     * ⭐ TRỪ TIỀN — hai CAS trong MỘT transaction.
     *
     *   CAS ①  bank_accounts     : WHERE balance >= amount   -> chống thấu chi
     *   CAS ②  bank_transactions : WHERE status = PENDING    -> chống chốt hai lần
     *
     * Hai CAS chống hai thứ KHÁC NHAU, cần cả hai (docs/notes/compare-and-set.md §5):
     * CAS ① không ngăn được việc trừ hai lần cho CÙNG một giao dịch — đó là việc của CAS ②.
     *
     * ⭐ Vì sao CAS ② thất bại thì được phép NÉM để rollback cả cú trừ tiền?
     * Vì cả hai câu lệnh nằm trong CÙNG MỘT DATABASE. Khác hẳn chỗ Hub gọi sang Bank:
     * ở đó rollback không huỷ được cú gọi mạng, nó chỉ xoá mất bằng chứng. Ở đây rollback
     * thực sự hoàn tác được. Cùng một công cụ, dùng đúng chỗ thì cứu, dùng sai chỗ thì hại.
     *
     * Ném ở TRONG transaction, bắt ở NGOÀI (controller) — đúng luật đã rút ra ở Inc 3,
     * xem docs/notes/spring-transactional-catch-boundary.md
     */
    @Transactional
    public PayResult pay(String bankCode, String bankTxnId, BankAccount account) {

        BankTransaction txn = transactions.findByBankTxnIdAndBankCode(bankTxnId, bankCode)
                .orElseThrow(() -> new IllegalArgumentException("Không có giao dịch " + bankTxnId));

        if (!txn.isPending()) {
            return new PayResult.AlreadyFinished(txn.getStatus());
        }

        long amount = txn.getAmount();
        Instant now = Instant.now();

        // ── CAS ①: trừ tiền. 0 dòng = không đủ số dư (KHÔNG phải lỗi) ──
        if (accounts.debit(account.getCode(), amount) == 0) {
            // ⭐ CỐ Ý KHÔNG chốt giao dịch ở đây. Giao dịch giữ nguyên PENDING để Ana
            // thử tài khoản khác. Chốt DECLINED ngay lần đầu hết tiền là đẩy khách vào
            // ngõ cụt: phải quay về merchant đặt lại đơn chỉ vì chọn nhầm tài khoản.
            // Giao dịch chỉ kết thúc khi Ana BẤM HUỶ, hoặc (sau này) hết hạn.
            log.info("[{}] {} lần Xác nhận thanh toánthử hỏng — {} (tài khoản {}), giao dịch vẫn PENDING",
                    bankCode, bankTxnId, DeclineReason.INSUFFICIENT_FUNDS.code(),
                    account.getCode());
            return new PayResult.AttemptFailed(DeclineReason.INSUFFICIENT_FUNDS);
        }

        // ── CAS ②: chốt giao dịch. 0 dòng = luồng khác đã chốt trước ──
        if (transactions.finish(bankTxnId, BankTransaction.Status.PENDING,
                BankTransaction.Status.SUCCESS, account.getCode(), null, now) == 0) {
            // Tiền vừa trừ ở CAS ① phải được trả lại. Ném ra để Spring rollback cả hai.
            log.warn("[{}] {} đã bị chốt bởi luồng khác — rollback cú trừ tiền", bankCode, bankTxnId);
            throw new ConcurrentFinishException();
        }

        // ⭐ OUTBOX: ghi ý định báo cho Hub, TRONG CÙNG transaction với cú trừ tiền.
        // Không gọi HTTP ở đây. Nếu gọi HTTP mà Hub chậm thì transaction bị kéo dài,
        // mà nếu Hub sập thì ta đứng trước lựa chọn tồi: rollback cú trừ tiền (sai, vì
        // tiền đã trừ thật) hay bỏ qua (sai, vì Hub không bao giờ biết).
        // Ghi một dòng DB thì không có lựa chọn tồi nào: cùng commit hoặc cùng rollback.
        recordEvent(txn, BankTransaction.Status.SUCCESS);

        // Đọc lại số dư CHỈ để hiển thị. Quyết định đã do CAS ① đưa ra rồi.
        // (JPQL không có RETURNING; dùng native RETURNING thì phải viết repository thủ công.)
        long newBalance = accounts.findById(account.getCode()).orElseThrow().getBalance();

        log.info("[{}] {} THÀNH CÔNG — trừ {} từ {}, còn {}",
                bankCode, bankTxnId, amount, account.getCode(), newBalance);
        return new PayResult.Paid(amount, newBalance);
    }

    /**
     * Ana bấm "Huỷ" — bỏ cuộc và quay về cửa hàng.
     *
     * IDEMPOTENT theo trạng thái: CAS chỉ huỷ được giao dịch đang PENDING. Bấm lần hai,
     * hoặc huỷ một giao dịch đã SUCCESS, đều trả về 0 dòng — không lỗi, và vẫn trả
     * return_url để trình duyệt đi tiếp. Người dùng luôn có đường ra.
     *
     * @return return_url để redirect, rỗng nếu không có giao dịch đó
     */
    @Transactional
    public Optional<String> cancel(String bankCode, String bankTxnId) {
        Optional<BankTransaction> found =
                transactions.findByBankTxnIdAndBankCode(bankTxnId, bankCode);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        int cancelled = transactions.finish(bankTxnId, BankTransaction.Status.PENDING,
                BankTransaction.Status.CANCELLED, null,
                DeclineReason.CANCELLED_BY_USER.code(), Instant.now());

        // Chỉ phát sự kiện khi TA là người đổi trạng thái. Bấm huỷ lần hai không được
        // đẻ thêm một callback nữa — Hub đã biết rồi.
        if (cancelled == 1) {
            recordEvent(found.get(), BankTransaction.Status.CANCELLED);
        }

        log.info("[{}] {} — Ana bấm huỷ ({})", bankCode, bankTxnId,
                cancelled == 1 ? "đã chuyển CANCELLED" : "giao dịch đã kết thúc từ trước");

        return Optional.of(found.get().getReturnUrl());
    }

    /**
     * Ghi một dòng outbox. Chạy TRONG transaction của hàm gọi — đó là cả mục đích.
     *
     * Payload được ĐÓNG BĂNG thành chuỗi ngay tại đây, không dựng lại lúc gửi:
     *   - worker ký trên ĐÚNG chuỗi này, byte khớp byte
     *   - dựng lại 10 phút sau có thể ra kết quả khác (trạng thái đã đổi tiếp)
     * Cùng nguyên tắc với idempotency_records.response_body bên Hub ở Inc 3.
     *
     * LinkedHashMap: thứ tự khoá cố định ⇒ chuỗi JSON xác định ⇒ chữ ký xác định.
     */
    private void recordEvent(BankTransaction txn, BankTransaction.Status status) {
        String eventId = "evt_" + UUID.randomUUID();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_id", eventId);
        body.put("bank_code", txn.getBankCode());
        body.put("bank_txn_id", txn.getBankTxnId());
        // Khoá Hub đã gửi sang lúc tạo giao dịch — Hub dùng nó để tra ngược payment_attempts.
        body.put("idempotency_key", txn.getIdempotencyKey());
        body.put("status", status.name());
        body.put("amount", txn.getAmount());          // minor unit, để Hub ĐỐI CHIẾU
        body.put("currency", txn.getCurrency());
        body.put("finished_at", Instant.now().toString());

        final String payload;
        try {
            payload = json.writeValueAsString(body);
        } catch (Exception e) {
            // Không serialize được là lỗi lập trình ⇒ phải nổ, không được nuốt:
            // nuốt ở đây nghĩa là trừ tiền xong mà không ai báo Hub.
            throw new IllegalStateException("Không dựng được payload callback", e);
        }

        outbox.save(new BankOutboxEvent(eventId, txn.getBankCode(), txn.getBankTxnId(),
                txn.getCallbackUrl(), payload));

        log.info("[{}] ghi outbox {} cho {} — status={}",
                txn.getBankCode(), eventId, txn.getBankTxnId(), status);
    }

    /** Bean riêng: @Transactional chỉ có hiệu lực khi gọi QUA PROXY, tức từ bean khác. */
    @Service
    public static class TransactionWriter {
        private final BankTransactionRepository transactions;

        public TransactionWriter(BankTransactionRepository transactions) {
            this.transactions = transactions;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public BankTransaction insert(BankTransaction txn) {
            // saveAndFlush: ép INSERT chạm DB NGAY để vi phạm UNIQUE nổ ở đây, không nổ lúc commit.
            return transactions.saveAndFlush(txn);
        }
    }
}
