package com.paymenthub.bank.domain;

import jakarta.persistence.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Entity
@Table(name = "bank_accounts")
public class BankAccount {

    public enum Status { ACTIVE, LOCKED }

    /**
     * "Núm vặn" giả lập — KHÔNG phải trạng thái nghiệp vụ.
     * Dùng để test được những ca mà số dư không tạo ra được.
     */
    public enum Behavior {
        NORMAL,   // trả lời bình thường
        SLOW,     // treo lâu  -> Hub timeout -> payment ở IN_PROGRESS + đối soát
        ERROR     // trả 5xx   -> Hub retry   -> circuit breaker
    }

    @Id
    private String code;

    @Column(name = "bank_code", nullable = false)
    private String bankCode;

    @Column(nullable = false)
    private String holder;

    /** Minor unit (CLAUDE.md #4). */
    @Column(nullable = false)
    private Long balance;

    /** ⚠️ DEV-ONLY: ngân hàng thật không lưu PIN kiểu này. */
    @Column(nullable = false)
    private String pin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Behavior behavior;

    protected BankAccount() { }

    public boolean isActive()   { return status == Status.ACTIVE; }

    /**
     * So sánh CONSTANT-TIME, cùng lý do với chữ ký HMAC (xem BankHmacVerifier):
     * String.equals thoát ngay khi gặp ký tự khác đầu tiên ⇒ thời gian chạy tiết lộ
     * đã khớp mấy ký tự ⇒ dò được từng ký tự thay vì phải thử đủ 10^6 tổ hợp.
     *
     * ⚠️ Chỉ cái này KHÔNG đủ chống dò PIN. Thứ thực sự chặn brute force là ĐẾM SỐ LẦN
     * SAI rồi khoá tài khoản — chưa làm, xem ghi chú ở AccountAuthenticator.
     */
    public boolean pinMatches(String input) {
        if (input == null) return false;
        return MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8),
                                     input.getBytes(StandardCharsets.UTF_8));
    }

    public String getCode()      { return code; }
    public String getBankCode()  { return bankCode; }
    public String getHolder()    { return holder; }
    public Long getBalance()     { return balance; }
    public Status getStatus()    { return status; }
    public Behavior getBehavior(){ return behavior; }
}
