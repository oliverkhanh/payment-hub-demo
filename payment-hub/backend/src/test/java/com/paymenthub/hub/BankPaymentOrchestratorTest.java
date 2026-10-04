package com.paymenthub.hub;

import com.paymenthub.hub.domain.PaymentAttempt;
import com.paymenthub.hub.domain.PaymentStatus;
import com.paymenthub.hub.domain.bank.BankConnector;
import com.paymenthub.hub.domain.bank.BankOutcome;
import com.paymenthub.hub.domain.bank.BankPaymentOrchestrator;
import com.paymenthub.hub.domain.bank.BankPaymentOrchestrator.PaymentRedirect;
import com.paymenthub.hub.domain.bank.BankTransactionRequest;
import com.paymenthub.hub.domain.bank.BankTransactionResult;
import com.paymenthub.hub.infra.PaymentAttemptRepository;
import com.paymenthub.hub.infra.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(BankPaymentOrchestratorTest.TestBanksConfig.class)
class BankPaymentOrchestratorTest extends AbstractIntegrationTest {

    static final String EXPLODING_BANK = "BOOM";
    static final String SILENT_BANK = "SILENT";
    static final String REFUSING_BANK = "REFUSER";
    static final String HAPPY_BANK = "HAPPY";

    static final String HAPPY_TXN_ID = "BNK-HAPPY-1";
    static final String HAPPY_REDIRECT = "http://bank.example/pay/BNK-HAPPY-1";

    private static final String DEAD_PORT = "http://127.0.0.1:1";

    @TestConfiguration
    static class TestBanksConfig {
        @Bean
        BankConnector explodingBankConnector() {
            return connector(EXPLODING_BANK, request -> {
                throw new IllegalStateException("bank nổ giữa chừng");
            });
        }

        @Bean
        BankConnector silentBankConnector() {
            return connector(SILENT_BANK,
                    request -> BankTransactionResult.indeterminate("test_no_answer", null));
        }

        @Bean
        BankConnector refusingBankConnector() {
            return connector(REFUSING_BANK,
                    request -> BankTransactionResult.declined("test_insufficient_funds", 200));
        }

        @Bean
        BankConnector happyBankConnector() {
            return connector(HAPPY_BANK,
                    request -> BankTransactionResult.accepted(HAPPY_TXN_ID, HAPPY_REDIRECT, 201));
        }

        private static BankConnector connector(String bankCode,
                                               java.util.function.Function<BankTransactionRequest,
                                                       BankTransactionResult> behaviour) {
            return new BankConnector() {
                @Override public String bankCode() { return bankCode; }
                @Override public BankTransactionResult createTransaction(BankTransactionRequest r) {
                    return behaviour.apply(r);
                }
            };
        }
    }

    @DynamicPropertySource
    static void testBanks(DynamicPropertyRegistry registry) {
        registerBank(registry, EXPLODING_BANK, "Bank Nổ", 91);
        registerBank(registry, SILENT_BANK, "Bank Im Lặng", 92);
        registerBank(registry, REFUSING_BANK, "Bank Từ Chối", 93);
        registerBank(registry, HAPPY_BANK, "Bank Thuận", 94);

        for (String realBank : new String[] {"VCB", "TCB", "ACB", "SCB"}) {
            registry.add("hub.banks." + realBank + ".base-url", () -> DEAD_PORT);
        }
    }

    private static void registerBank(DynamicPropertyRegistry registry, String code,
                                     String displayName, int order) {
        registry.add("hub.banks." + code + ".display-name", () -> displayName);
        registry.add("hub.banks." + code + ".enabled", () -> "true");
        registry.add("hub.banks." + code + ".order", () -> String.valueOf(order));
        registry.add("hub.banks." + code + ".base-url", () -> DEAD_PORT);
        registry.add("hub.banks." + code + ".api-key", () -> "k");
        registry.add("hub.banks." + code + ".api-secret", () -> "s");
    }

    @Autowired BankPaymentOrchestrator orchestrator;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentAttemptRepository paymentAttemptRepository;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx() { return new TransactionTemplate(txManager); }

    private String newCheckoutToken() {
        var created = postPayment(UUID.randomUUID().toString(),
                createBody("ord_orch_" + UUID.randomUUID(), 150000));
        String url = HmacAuthenticationTest.extract(created.getBody(), "checkout_url");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private Long paymentIdOf(String token) {
        return tx().execute(s -> paymentRepository.findByCheckoutToken(token).orElseThrow().getId());
    }

    private PaymentStatus statusOf(Long paymentId) {
        PaymentStatus status = tx().execute(s ->
                paymentRepository.findById(paymentId).orElseThrow().getStatus());
        return status;
    }

    private long attemptCount(Long paymentId) {
        Long count = tx().execute(s -> paymentAttemptRepository.findAll().stream()
                .filter(a -> a.getPaymentId().equals(paymentId)).count());
        return count;
    }

    private PaymentAttempt lastAttemptOf(Long paymentId) {
        return tx().execute(s ->
                paymentAttemptRepository.findTopByPaymentIdOrderByAttemptNoDesc(paymentId)
                        .orElseThrow());
    }

    @Test
    @DisplayName("⭐ Adapter nổ giữa chừng — ①② VẪN COMMIT, và attempt được đóng INDETERMINATE")
    void bankCallHappensAfterCommit() {
        String token = newCheckoutToken();
        Long paymentId = paymentIdOf(token);

        assertThatThrownBy(() -> orchestrator.pay(token, EXPLODING_BANK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nổ giữa chừng");

        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(attemptCount(paymentId)).isEqualTo(1);

        PaymentAttempt attempt = lastAttemptOf(paymentId);
        assertThat(attempt.getBankCode()).isEqualTo(EXPLODING_BANK);
        assertThat(attempt.getIdempotencyKey()).startsWith("att_");
        assertThat(attempt.getOutcome()).isEqualTo(BankOutcome.INDETERMINATE);
        assertThat(attempt.getErrorCode()).startsWith("adapter_exception:");
        assertThat(attempt.getAnsweredAt()).isNotNull();
        assertThat(attempt.isInDoubt()).isTrue();
    }

    @Test
    @DisplayName("⭐ NOT_SENT -> nhả claim về CREATED, khách chọn được bank khác (attempt #2)")
    void notSentReleasesClaimSoUserCanPickAnotherBank() {
        String token = newCheckoutToken();
        Long paymentId = paymentIdOf(token);

        orchestrator.pay(token, "VCB");

        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.CREATED);
        assertThat(lastAttemptOf(paymentId).getOutcome()).isEqualTo(BankOutcome.NOT_SENT);

        PaymentRedirect second = orchestrator.pay(token, HAPPY_BANK);

        assertThat(second).isInstanceOf(PaymentRedirect.ToBank.class);
        assertThat(attemptCount(paymentId)).isEqualTo(2);
        assertThat(lastAttemptOf(paymentId).getAttemptNo()).isEqualTo(2);
        assertThat(lastAttemptOf(paymentId).getBankCode()).isEqualTo(HAPPY_BANK);
    }

    @Test
    @DisplayName("⭐ ACCEPTED -> redirect sang bank, lưu bank_txn_id + redirect_url, giữ IN_PROGRESS")
    void acceptedStoresBankTxnIdAndRedirects() {
        String token = newCheckoutToken();
        Long paymentId = paymentIdOf(token);

        PaymentRedirect redirect = orchestrator.pay(token, HAPPY_BANK);

        assertThat(redirect).isEqualTo(new PaymentRedirect.ToBank(HAPPY_REDIRECT));
        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.IN_PROGRESS);

        PaymentAttempt attempt = lastAttemptOf(paymentId);
        assertThat(attempt.getOutcome()).isEqualTo(BankOutcome.ACCEPTED);
        assertThat(attempt.getBankTxnId()).isEqualTo(HAPPY_TXN_ID);
        assertThat(attempt.getRedirectUrl()).isEqualTo(HAPPY_REDIRECT);
        assertThat(attempt.getHttpStatus()).isEqualTo(201);
        assertThat(attempt.getErrorCode()).isNull();
        assertThat(attempt.isInDoubt()).isFalse();
    }

    @Test
    @DisplayName("DECLINED -> payment FAILED, lưu lại mã lỗi của bank")
    void declinedMarksPaymentFailed() {
        String token = newCheckoutToken();
        Long paymentId = paymentIdOf(token);

        PaymentRedirect redirect = orchestrator.pay(token, REFUSING_BANK);

        assertThat(redirect).isInstanceOf(PaymentRedirect.ToCheckout.class);
        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.FAILED);

        PaymentAttempt attempt = lastAttemptOf(paymentId);
        assertThat(attempt.getOutcome()).isEqualTo(BankOutcome.DECLINED);
        assertThat(attempt.getErrorCode()).isEqualTo("test_insufficient_funds");
        assertThat(attempt.isInDoubt()).isFalse();
    }

    @Test
    @DisplayName("INDETERMINATE -> GIỮ IN_PROGRESS, attempt còn in-doubt cho job đối soát")
    void indeterminateKeepsPaymentInProgress() {
        String token = newCheckoutToken();
        Long paymentId = paymentIdOf(token);

        orchestrator.pay(token, SILENT_BANK);

        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.IN_PROGRESS);

        PaymentAttempt attempt = lastAttemptOf(paymentId);
        assertThat(attempt.getOutcome()).isEqualTo(BankOutcome.INDETERMINATE);
        assertThat(attempt.isInDoubt()).isTrue();
    }

    @Test
    @DisplayName("Mỗi bank ghi đúng bank_code của mình vào attempt -> Strategy định tuyến đúng")
    void routesToTheChosenBank() {
        for (String bankCode : new String[] {"VCB", "TCB", "ACB", "SCB"}) {
            String token = newCheckoutToken();
            Long paymentId = paymentIdOf(token);

            orchestrator.pay(token, bankCode);

            PaymentAttempt attempt = lastAttemptOf(paymentId);
            assertThat(attempt.getBankCode()).as("bank %s", bankCode).isEqualTo(bankCode);
            assertThat(attempt.getOutcome()).as("bank %s", bankCode)
                    .isEqualTo(BankOutcome.NOT_SENT);
            assertThat(statusOf(paymentId)).as("bank %s", bankCode)
                    .isEqualTo(PaymentStatus.CREATED);
        }
    }

    @Test
    @DisplayName("Bank không nghe -> về trang checkout kèm thông báo, không redirect tới null")
    void unreachableBankGoesBackToCheckout() {
        String token = newCheckoutToken();

        PaymentRedirect redirect = orchestrator.pay(token, "VCB");

        assertThat(redirect).isInstanceOf(PaymentRedirect.ToCheckout.class);
        assertThat(((PaymentRedirect.ToCheckout) redirect).notice()).isNotBlank();
    }

    @Test
    @DisplayName("bank_code không hợp lệ -> dừng TRƯỚC khi đụng DB, không gọi adapter nào")
    void invalidBankCodeNeverReachesAdapter() {
        String token = newCheckoutToken();
        Long paymentId = paymentIdOf(token);

        PaymentRedirect redirect = orchestrator.pay(token, "MB");

        assertThat(redirect).isInstanceOf(PaymentRedirect.ToCheckout.class);
        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.CREATED);
        assertThat(attemptCount(paymentId)).isZero();
    }

    @Test
    @DisplayName("Payment còn IN_PROGRESS -> bấm lần hai bị Refused, không gọi bank lần nữa")
    void secondClickDoesNotCallBankAgain() {
        String token = newCheckoutToken();
        Long paymentId = paymentIdOf(token);

        orchestrator.pay(token, SILENT_BANK);
        PaymentRedirect second = orchestrator.pay(token, SILENT_BANK);

        assertThat(second).isInstanceOf(PaymentRedirect.ToCheckout.class);
        assertThat(((PaymentRedirect.ToCheckout) second).notice()).contains("đang được xử lý");
        assertThat(attemptCount(paymentId)).isEqualTo(1);
    }
}
