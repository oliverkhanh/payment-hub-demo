package com.paymenthub.bank;

import com.paymenthub.bank.domain.Account;
import com.paymenthub.bank.domain.BankTransaction;
import com.paymenthub.bank.domain.BankTransactionStatus;
import com.paymenthub.bank.domain.CancelFailedException;
import com.paymenthub.bank.domain.CancelService;
import com.paymenthub.bank.domain.DebitFailedException;
import com.paymenthub.bank.domain.DebitService;
import com.paymenthub.bank.domain.NewTransaction;
import com.paymenthub.bank.domain.OutboxEvent;
import com.paymenthub.bank.infra.AccountRepository;
import com.paymenthub.bank.infra.BankTransactionRepository;
import com.paymenthub.bank.infra.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebitAndCancelTest extends AbstractBankIntegrationTest {

    private static final long AMOUNT = 150_000L;

    @Autowired DebitService debitService;
    @Autowired CancelService cancelService;
    @Autowired AccountRepository accountRepository;
    @Autowired BankTransactionRepository bankTransactionRepository;
    @Autowired OutboxRepository outboxRepository;

    @Test
    @DisplayName("Trừ tiền: số dư giảm + giao dịch SUCCESS + outbox — cùng một transaction")
    void debit_ghi_ca_ba_thu() {
        Account account = activeAccount();
        long balanceTruoc = account.getBalance();
        BankTransaction txn = pendingTransaction();

        debitService.debit(account.getId(), txn);

        assertThat(balanceOf(account.getId())).isEqualTo(balanceTruoc - AMOUNT);
        assertThat(statusOf(txn)).isEqualTo(BankTransactionStatus.SUCCESS);
        assertThat(outboxOf(txn)).singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo("BankTransaction.Succeeded");
                    assertThat(event.getPayload()).contains("\"amount\":" + AMOUNT);
                });
    }

    @Test
    @DisplayName("Trừ tiền hai lần trên cùng giao dịch: lần hai bị CAS chặn")
    void debit_hai_lan_bi_chan() {
        Account account = activeAccount();
        BankTransaction txn = pendingTransaction();

        debitService.debit(account.getId(), txn);
        long balanceSauLanMot = balanceOf(account.getId());

        assertThatThrownBy(() -> debitService.debit(account.getId(), txn))
                .isInstanceOf(DebitFailedException.class);

        assertThat(balanceOf(account.getId())).isEqualTo(balanceSauLanMot);
        assertThat(outboxOf(txn)).hasSize(1);
    }

    @Test
    @DisplayName("Không đủ tiền: không trừ, giao dịch vẫn PENDING, không có outbox")
    void khong_du_tien_thi_khong_ghi_gi() {
        Account poor = accountRepository
                .findByBankCodeAndUsernameAndPin("VCB", "vcb_user_poor", "123456").orElseThrow();
        BankTransaction txn = pendingTransaction();

        assertThatThrownBy(() -> debitService.debit(poor.getId(), txn))
                .isInstanceOf(DebitFailedException.class);

        assertThat(statusOf(txn)).isEqualTo(BankTransactionStatus.PENDING);
        assertThat(outboxOf(txn)).isEmpty();
    }

    @Test
    @DisplayName("Huỷ: giao dịch CANCELLED + outbox Cancelled")
    void huy_ghi_outbox_cancelled() {
        BankTransaction txn = pendingTransaction();

        cancelService.cancel(txn);

        assertThat(statusOf(txn)).isEqualTo(BankTransactionStatus.CANCELLED);
        assertThat(outboxOf(txn)).singleElement()
                .satisfies(event ->
                        assertThat(event.getEventType()).isEqualTo("BankTransaction.Cancelled"));
    }

    @Test
    @DisplayName("Đã trừ tiền rồi thì không huỷ được nữa")
    void da_tru_tien_thi_khong_huy_duoc() {
        Account account = activeAccount();
        BankTransaction txn = pendingTransaction();
        debitService.debit(account.getId(), txn);

        assertThatThrownBy(() -> cancelService.cancel(txn))
                .isInstanceOf(CancelFailedException.class);

        assertThat(statusOf(txn)).isEqualTo(BankTransactionStatus.SUCCESS);
        assertThat(outboxOf(txn)).hasSize(1);
    }

    private Account activeAccount() {
        return accountRepository
                .findByBankCodeAndUsernameAndPin("VCB", "vcb_user_active", "123456").orElseThrow();
    }

    private long balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    private BankTransactionStatus statusOf(BankTransaction txn) {
        return bankTransactionRepository.findById(txn.getId()).orElseThrow().getStatus();
    }

    private List<OutboxEvent> outboxOf(BankTransaction txn) {
        return outboxRepository.findAll().stream()
                .filter(event -> event.getAggregateId().equals(txn.getBankTxnRef()))
                .toList();
    }

    private BankTransaction pendingTransaction() {
        String ref = "VCB-TEST-" + UUID.randomUUID().toString().substring(0, 8);
        return bankTransactionRepository.save(new BankTransaction("VCB", ref,
                new NewTransaction(UUID.randomUUID().toString(), AMOUNT, "VND", "Shop",
                        "http://payment-hub:8080/bank-callbacks/VCB",
                        "http://localhost:8080/return")));
    }
}
