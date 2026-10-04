package com.paymenthub.bank.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

@Getter
@Entity
@Table(name = "accounts")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_code", nullable = false, updatable = false)
    private String bankCode;

    @Column(name = "username", nullable = false, updatable = false)
    private String username;

    @Column(name = "pin", nullable = false)
    private String pin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Column(name = "is_slow", nullable = false)
    private boolean slow;

    @Column(name = "is_error_account", nullable = false)
    private boolean errorAccount;

    @Column(name = "created_date", nullable = false, insertable = false, updatable = false)
    private Instant createdDate;

    @Column(name = "updated_date", nullable = false, insertable = false)
    private Instant updatedDate;

    protected Account() { }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
