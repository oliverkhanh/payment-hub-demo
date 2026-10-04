package com.paymenthub.hub.api;

import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.domain.bank.BankPaymentOrchestrator;
import com.paymenthub.hub.domain.bank.BankPaymentOrchestrator.PaymentRedirect;
import com.paymenthub.hub.domain.BusinessException;
import com.paymenthub.hub.domain.Payment;
import com.paymenthub.hub.domain.PaymentService;
import com.paymenthub.hub.domain.PaymentStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class CheckoutController {
    private final PaymentService paymentService;
    private final HubBankProperties banks;
    private final BankPaymentOrchestrator bankPaymentOrchestrator;
    private final PaymentStatusWatch paymentStatusWatch;

    @Value("${hub.checkout.long-poll-timeout-ms:25000}")
    private final long longPollTimeoutMs;

    @GetMapping("/checkout/{checkoutToken}")
    public String show(@PathVariable String checkoutToken, Model model) {
        final Payment payment;
        try {
            payment = paymentService.loadForCheckout(checkoutToken);
        } catch (BusinessException notFound) {
            model.addAttribute("reason", "Link thanh toán không tồn tại.");
            return "checkout-unavailable";
        }

        if (payment.getStatus() != PaymentStatus.CREATED) {
            model.addAttribute("reason", reasonFor(payment.getStatus()));
            model.addAttribute("returnUrl", payment.getReturnUrl());
            return "checkout-unavailable";
        }

        model.addAttribute("payment", payment);
        model.addAttribute("checkoutToken", checkoutToken);

        model.addAttribute("banks", banks.enabledOptions());

        return "checkout";
    }

    @GetMapping("/checkout/{checkoutToken}/bank-return")
    public String bankReturn(@PathVariable String checkoutToken, Model model) {
        final Payment payment;
        try {
            payment = paymentService.loadForCheckout(checkoutToken);
        } catch (BusinessException notFound) {
            model.addAttribute("reason", "Link thanh toán không tồn tại.");
            return "checkout-unavailable";
        }

        model.addAttribute("payment", payment);
        model.addAttribute("checkoutToken", checkoutToken);
        model.addAttribute("returnUrl", payment.getReturnUrl());
        model.addAttribute("headline", headlineFor(payment.getStatus()));
        model.addAttribute("detail", detailFor(payment.getStatus()));
        model.addAttribute("tone", toneFor(payment.getStatus()));

        return "bank-return";
    }

    @GetMapping("/checkout/{checkoutToken}/status")
    @ResponseBody
    public DeferredResult<PaymentStatusView> status(@PathVariable String checkoutToken,
                                                    @RequestParam("seen") String seenStatus) {
        final Payment payment;
        try {
            payment = paymentService.loadForCheckout(checkoutToken);
        } catch (BusinessException notFound) {
            DeferredResult<PaymentStatusView> gone = new DeferredResult<>();
            gone.setResult(new PaymentStatusView("UNKNOWN"));
            return gone;
        }

        String currentStatus = payment.getStatus().name();

        DeferredResult<PaymentStatusView> pending =
                new DeferredResult<>(longPollTimeoutMs, () -> new PaymentStatusView(currentStatus));

        if (!currentStatus.equals(seenStatus)) {
            pending.setResult(new PaymentStatusView(currentStatus));
            return pending;
        }

        paymentStatusWatch.register(checkoutToken, seenStatus, pending);
        return pending;
    }

    private static String headlineFor(PaymentStatus status) {
        return switch (status) {
            case CAPTURED    -> "Thanh toán thành công";
            case IN_PROGRESS -> "Đang chờ ngân hàng xác nhận";
            case FAILED      -> "Thanh toán thất bại";
            case CANCELLED   -> "Giao dịch đã huỷ";
            case EXPIRED     -> "Link thanh toán đã hết hạn";
            case CREATED     -> "Chưa có giao dịch nào được thực hiện";
            default          -> "Trạng thái giao dịch";
        };
    }

    private static String detailFor(PaymentStatus status) {
        return switch (status) {
            case CAPTURED    -> "Ngân hàng đã báo về và giao dịch đã được ghi nhận.";
            case IN_PROGRESS -> "Ngân hàng đang gửi kết quả về. Trạng thái sẽ được cập nhật "
                                + "ngay khi nhận được xác nhận.";
            case FAILED      -> "Giao dịch không thành công. Vui lòng thử lại từ đơn hàng.";
            case CANCELLED   -> "Bạn đã huỷ giao dịch này.";
            case EXPIRED     -> "Link thanh toán đã hết hiệu lực. Vui lòng đặt lại đơn hàng.";
            case CREATED     -> "Chưa có ngân hàng nào được chọn cho đơn hàng này.";
            default          -> "";
        };
    }

    private static String toneFor(PaymentStatus status) {
        return switch (status) {
            case CAPTURED              -> "ok";
            case IN_PROGRESS, CREATED  -> "pending";
            default                    -> "fail";
        };
    }

    @PostMapping("/checkout/{checkoutToken}/pay")
    public String pay(@PathVariable String checkoutToken,
                      @RequestParam("bank_code") String bankCode,
                      RedirectAttributes redirect) {
        final PaymentRedirect next;
        try {
            next = bankPaymentOrchestrator.pay(checkoutToken, bankCode);
        } catch (UnsupportedOperationException notImplementedYet) {
            redirect.addFlashAttribute("notice",
                    "Đã giành quyền gọi ngân hàng và ghi nhật ký. Adapter "
                            + bankCode + " chưa cài đặt nên chưa gọi được: "
                            + notImplementedYet.getMessage());
            redirect.addFlashAttribute("selectedBankCode", bankCode);
            return "redirect:/checkout/" + checkoutToken;
        }

        return switch (next) {
            case PaymentRedirect.ToBank bank -> "redirect:" + bank.url();
            case PaymentRedirect.ToCheckout back -> {
                redirect.addFlashAttribute("notice", back.notice());
                redirect.addFlashAttribute("selectedBankCode", bankCode);
                yield "redirect:/checkout/" + checkoutToken;
            }
        };
    }

    @PostMapping("/checkout/{checkoutToken}/cancel")
    public RedirectView cancel(@PathVariable String checkoutToken) {
        Payment payment = paymentService.cancelByCustomer(checkoutToken);
        return new RedirectView(appendStatus(payment.getReturnUrl(), payment));
    }

    private static String appendStatus(String returnUrl, Payment payment) {
        String separator = returnUrl.contains("?") ? "&" : "?";
        return returnUrl + separator
                + "payment_ref=" + encode(payment.getPaymentRef())
                + "&status=" + payment.getStatus().name();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String reasonFor(PaymentStatus status) {
        return switch (status) {
            case EXPIRED     -> "Link thanh toán đã hết hạn. Vui lòng đặt lại đơn hàng.";
            case CANCELLED   -> "Bạn đã hủy giao dịch này.";
            case CAPTURED    -> "Đơn hàng này đã được thanh toán.";
            case FAILED      -> "Giao dịch trước đã thất bại. Vui lòng thử lại từ đơn hàng.";
            case IN_PROGRESS -> "Giao dịch đang được xử lý. Vui lòng chờ trong ít phút.";
            default          -> "Link thanh toán không còn hiệu lực.";
        };
    }
}
