package com.paymenthub.bank.api;

import com.paymenthub.bank.domain.AccountAuthenticator;
import com.paymenthub.bank.domain.AccountAuthenticator.AuthResult;
import com.paymenthub.bank.domain.BankAccount;
import com.paymenthub.bank.domain.BankTransaction;
import com.paymenthub.bank.infra.BankAccountRepository;
import com.paymenthub.bank.infra.security.BankCredentialProperties;
import com.paymenthub.bank.domain.TransactionService;
import com.paymenthub.bank.domain.TransactionService.PayResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * Trang thanh toán của ngân hàng — nơi Ana nhập mã tài khoản + PIN.
 *
 * ⚠️ Trang này cho TRÌNH DUYỆT, KHÔNG xác thực HMAC: Ana không có secret của Hub.
 * "Vé vào cửa" ở đây là bank_txn_id trong URL — đúng vai trò checkout_token bên Hub.
 *
 * ⭐ RANH GIỚI DỮ LIỆU (spec §2.6): mã tài khoản và PIN chỉ tồn tại ở ĐÂY. Hub không
 * bao giờ thấy chúng — Hub chỉ biết khách trả QUA ĐÂU, không biết trả BẰNG GÌ.
 * Đó chính là cách gateway thật né phạm vi PCI DSS.
 *
 * Thành phần hiện tại: CHỈ hiển thị form và nhận dữ liệu.
 * Thành phần sau: kiểm tài khoản/PIN/số dư → trừ tiền → callback về Hub → redirect.
 */
@Controller
public class BankCheckoutController {

    private static final Logger log = LoggerFactory.getLogger(BankCheckoutController.class);

    private final TransactionService transactions;
    private final AccountAuthenticator authenticator;
    private final BankAccountRepository accounts;
    private final BankCredentialProperties banks;

    public BankCheckoutController(TransactionService transactions,
                                  AccountAuthenticator authenticator,
                                  BankAccountRepository accounts,
                                  BankCredentialProperties banks) {
        this.transactions = transactions;
        this.authenticator = authenticator;
        this.accounts = accounts;
        this.banks = banks;
    }

    @GetMapping("/banks/{bankCode}/checkout/{bankTxnId}")
    public String show(@PathVariable String bankCode, @PathVariable String bankTxnId,
                       Model model) {
        return render(bankCode, bankTxnId, model, null, null, null);
    }

    /**
     * Ana bấm "Xác nhận thanh toán".
     *
     * ⚠️ THÀNH PHẦN NÀY: xác thực chủ tài khoản (tồn tại → PIN → đang mở).
     * CHƯA kiểm số dư, CHƯA trừ tiền — đó là thành phần sau.
     *
     * ⭐ Sai PIN KHÔNG làm hỏng giao dịch: bank_transactions vẫn PENDING, Ana gõ lại
     * được ngay. Đánh DECLINED ở đây là bắt khách quay về merchant đặt lại đơn chỉ vì
     * một lần gõ nhầm.
     */
    @PostMapping("/banks/{bankCode}/checkout/{bankTxnId}")
    public String submit(@PathVariable String bankCode, @PathVariable String bankTxnId,
                         @RequestParam(value = "account_code", required = false) String accountCode,
                         @RequestParam(value = "pin", required = false) String pin,
                         Model model) {

        String code = accountCode == null ? "" : accountCode.trim().toUpperCase();
        String secret = pin == null ? "" : pin.trim();

        if (code.isEmpty() || secret.isEmpty()) {
            return render(bankCode, bankTxnId, model, code,
                    "Vui lòng nhập cả mã tài khoản và mã PIN.", null);
        }

        // ⭐ Ghi log mã tài khoản thì được, PIN thì TUYỆT ĐỐI KHÔNG.
        // PIN lọt vào log là lọt vào mọi chỗ log chảy tới: file, CloudWatch, Grafana,
        // và không bao giờ xoá hết được. Cùng lý do với PAN/CVV (CLAUDE.md #5).
        log.info("[{}] nhận thông tin thanh toán cho {} — account={}, pin=<không ghi log>",
                bankCode, bankTxnId, code);

        var auth = authenticator.authenticate(bankCode, code, secret);
        if (auth instanceof AuthResult.Rejected rejected) {
            // Message lấy TỪ DeclineReason, không tự chế tại đây — nếu mỗi nơi tự viết
            // một câu thì sớm muộn có nơi lỡ nói rõ "sai PIN" và làm lộ mã có thật.
            return render(bankCode, bankTxnId, model, code, rejected.reason().message(), null);
        }
        BankAccount account = ((AuthResult.Ok) auth).account();

        // ⭐ BẮT exception Ở ĐÂY — ngoài transaction. Ném ở trong, bắt ở ngoài
        // (docs/notes/spring-transactional-catch-boundary.md).
        final PayResult result;
        try {
            result = transactions.pay(bankCode, bankTxnId, account);
        } catch (TransactionService.ConcurrentFinishException raced) {
            return render(bankCode, bankTxnId, model, code,
                    "Giao dịch đang được xử lý. Vui lòng tải lại trang.", null);
        }

        return switch (result) {
            case PayResult.Paid paid -> {
                model.addAttribute("txnId", bankTxnId);
                model.addAttribute("bankName", banks.displayName(bankCode));
                model.addAttribute("bankCode", bankCode);
                model.addAttribute("accountCode", account.getCode());
                model.addAttribute("holder", account.getHolder());
                model.addAttribute("amount", paid.amount());
                model.addAttribute("newBalance", paid.newBalance());
                yield "bank-result";
            }
            // Lần thử hỏng (hết tiền) ⇒ Ở LẠI form, giao dịch vẫn PENDING,
            // Ana nhập tài khoản khác được ngay. Giống hệt cách xử lý sai PIN.
            case PayResult.AttemptFailed failed ->
                    render(bankCode, bankTxnId, model, code, failed.reason().message(), null);

            case PayResult.AlreadyFinished already ->
                    finished(bankCode, bankTxnId, model, "Giao dịch này đã được xử lý.");
        };
    }

    /**
     * Ana bấm "Huỷ giao dịch" — luôn có đường ra, kể cả khi giao dịch đã kết thúc.
     *
     * return_url lấy từ DB, KHÔNG lấy từ query param — nếu không thì trang bank trở thành
     * open redirect (cùng nguyên tắc với CheckoutController bên Hub).
     */
    @PostMapping("/banks/{bankCode}/checkout/{bankTxnId}/cancel")
    public String cancel(@PathVariable String bankCode, @PathVariable String bankTxnId,
                         Model model) {
        return transactions.cancel(bankCode, bankTxnId)
                .map(returnUrl -> "redirect:" + returnUrl)
                .orElseGet(() -> {
                    model.addAttribute("reason", "Giao dịch không tồn tại.");
                    return "bank-unavailable";
                });
    }

    private String finished(String bankCode, String bankTxnId, Model model, String reason) {
        model.addAttribute("reason", reason);
        transactions.findForCheckout(bankCode, bankTxnId)
                .ifPresent(t -> model.addAttribute("returnUrl", t.getReturnUrl()));
        return "bank-unavailable";
    }

    /**
     * Dựng trang. Dùng chung cho GET và POST để form hiện lại được kèm thông báo —
     * không redirect, vì ta cần giữ nguyên dữ liệu Ana vừa nhập.
     */
    private String render(String bankCode, String bankTxnId, Model model,
                          String accountCode, String error, String notice) {

        if (!banks.supports(bankCode)) {
            model.addAttribute("reason", "Giao dịch không tồn tại.");
            return "bank-unavailable";
        }

        Optional<BankTransaction> found = transactions.findForCheckout(bankCode, bankTxnId);
        if (found.isEmpty()) {
            // Không nói "mã sai định dạng" hay tương tự — không giúp người dò mã.
            model.addAttribute("reason", "Giao dịch không tồn tại.");
            return "bank-unavailable";
        }

        BankTransaction txn = found.get();
        if (!txn.isPending()) {
            // Giao dịch đã xử lý xong rồi: không cho nhập lại tài khoản lần nữa.
            model.addAttribute("reason", reasonFor(txn));
            model.addAttribute("returnUrl", txn.getReturnUrl());
            return "bank-unavailable";
        }

        model.addAttribute("txn", txn);
        model.addAttribute("bankCode", bankCode);
        model.addAttribute("bankName", banks.displayName(bankCode));
        model.addAttribute("accountCode", accountCode);
        model.addAttribute("error", error);
        model.addAttribute("notice", notice);
        // Bảng "tài khoản thử nghiệm" — cùng ý tưởng thẻ test của Stripe sandbox.
        // CHỈ tài khoản của chính bank này: mỗi bank là một hệ thống riêng.
        var testAccounts = accounts.findByBankCodeOrderByCode(bankCode);
        model.addAttribute("testAccounts", testAccounts);
        // Chỉ chú thích "núm vặn" mà bank này thực sự có tài khoản tương ứng.
        model.addAttribute("hasSlowAccount", testAccounts.stream()
                .anyMatch(a -> a.getBehavior() == BankAccount.Behavior.SLOW));
        model.addAttribute("hasErrorAccount", testAccounts.stream()
                .anyMatch(a -> a.getBehavior() == BankAccount.Behavior.ERROR));
        return "bank-checkout";
    }

    private static String reasonFor(BankTransaction txn) {
        return switch (txn.getStatus()) {
            case SUCCESS   -> "Giao dịch này đã được thanh toán.";
            case DECLINED  -> "Giao dịch này đã bị từ chối.";
            case CANCELLED -> "Giao dịch này đã bị hủy.";
            case PENDING   -> "";   // không tới đây
        };
    }
}
