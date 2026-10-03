package com.paymenthub.hub.api;

import com.paymenthub.hub.domain.BusinessException;
import com.paymenthub.hub.domain.Payment;
import com.paymenthub.hub.domain.PaymentService;
import com.paymenthub.hub.domain.PaymentStatus;
import com.paymenthub.hub.domain.bank.BankPaymentService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Hosted checkout — cho TRÌNH DUYỆT của Ana (chuỗi filter /checkout/**, KHÔNG HMAC).
 * Server-rendered Thymeleaf theo CLAUDE.md §3 (trang form/kết quả đơn giản).
 *
 * ⚠️ permitAll() ở SecurityConfig KHÔNG có nghĩa là không xác thực.
 * Xác thực của trang này CHÍNH LÀ checkout_token — vé vào cửa (capability). Spring Security
 * không biết gì về nó, nên service phải tự kiểm: tồn tại? còn hạn? đúng trạng thái?
 */
@Controller
public class CheckoutController {

    private final PaymentService paymentService;
    private final com.paymenthub.hub.config.HubBankProperties banks;
    private final BankPaymentService bankPayments;

    public CheckoutController(PaymentService paymentService,
                              com.paymenthub.hub.config.HubBankProperties banks,
                              BankPaymentService bankPayments) {
        this.paymentService = paymentService;
        this.banks = banks;
        this.bankPayments = bankPayments;
    }

    @GetMapping("/checkout/{checkoutToken}")
    public String show(@PathVariable String checkoutToken, Model model) {
        final Payment payment;
        try {
            payment = paymentService.loadForCheckout(checkoutToken);
        } catch (BusinessException notFound) {
            // Token sai/không tồn tại: KHÔNG nói "token không đúng định dạng" hay tương tự.
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
        // Increment 4: danh sách ngân hàng thuộc về HUB, không phải merchant.
        // Hub thêm/tắt bank chỉ cần đổi config, merchant không phải deploy lại (spec §2.4).
        model.addAttribute("banks", banks.enabledOptions());
        // KHÔNG đưa ra view: payment.id, merchant secret, danh sách payment khác.
        return "checkout";
    }

    /**
     * Ana bấm "Thanh toán" sau khi chọn ngân hàng.
     *
     * Controller mỏng CÓ CHỦ ĐÍCH: nó chỉ dịch kết quả nghiệp vụ thành một lệnh redirect.
     * Toàn bộ CAS, ghi intent log, gọi Bank, xử lý in-doubt nằm ở BankPaymentService —
     * nếu mai có thêm API JSON cho mobile thì tầng đó dùng lại được nguyên vẹn.
     */
    @PostMapping("/checkout/{checkoutToken}/pay")
    public String pay(@PathVariable String checkoutToken,
                      @RequestParam("bank_code") String bankCode,
                      RedirectAttributes redirect) {

        // switch trên sealed interface: compiler kiểm hộ đã phủ hết nhánh.
        // Thêm nhánh thứ ba ở Redirect ⇒ build đỏ ngay tại đây, không nổ lúc chạy.
        return switch (bankPayments.pay(checkoutToken, bankCode)) {
            // redirect_url do Bank cấp — dùng NGUYÊN XI, không ghép thêm query.
            case BankPaymentService.Redirect.ToBank bank -> "redirect:" + bank.url();
            case BankPaymentService.Redirect.ToCheckout back -> {
                redirect.addFlashAttribute("notice", back.notice());
                yield "redirect:/checkout/" + checkoutToken;
            }
        };
    }

    /**
     * Bank đẩy TRÌNH DUYỆT của Ana về đây — KÊNH B.
     *
     * ⚠️ Kênh này chỉ để HIỂN THỊ, KHÔNG phải nguồn sự thật. Ana (hoặc kẻ tấn công)
     * gõ tay URL này được, nên Hub TUYỆT ĐỐI không đổi trạng thái payment dựa vào nó.
     * Sự thật đến từ KÊNH A: callback server→server có ký HMAC — chưa làm, thành phần sau.
     *
     * Vì vậy trang này chỉ đọc trạng thái Hub ĐANG BIẾT và luôn cho một đường về cửa hàng.
     */
    @GetMapping("/checkout/{checkoutToken}/bank-return")
    public String bankReturn(@PathVariable String checkoutToken, Model model) {
        final Payment payment;
        try {
            payment = paymentService.loadForCheckout(checkoutToken);
        } catch (BusinessException notFound) {
            model.addAttribute("reason", "Link thanh toán không tồn tại.");
            return "checkout-return";
        }

        model.addAttribute("status", payment.getStatus());
        model.addAttribute("reason", returnReasonFor(payment.getStatus()));
        model.addAttribute("returnUrl", payment.getReturnUrl());
        return "checkout-return";
    }

    private static String returnReasonFor(PaymentStatus status) {
        return switch (status) {
            // Trạng thái THẬT của hệ thống lúc này: bank có thể đã trừ tiền xong, nhưng
            // Hub chưa nhận được callback nên chưa dám kết luận. Nói đúng như thế.
            case IN_PROGRESS -> "Đang xác minh kết quả với ngân hàng.";
            case CAPTURED    -> "Đơn hàng đã được thanh toán.";
            case FAILED      -> "Giao dịch không thành công.";
            case CANCELLED   -> "Bạn đã hủy giao dịch này.";
            case EXPIRED     -> "Link thanh toán đã hết hạn.";
            case CREATED     -> "Chưa có kết quả thanh toán cho đơn này.";
            default          -> "Chưa có kết quả thanh toán.";
        };
    }

    /**
     * Ana bấm "quay lại". Redirect 302 (không JSON) vì client là trình duyệt.
     * return_url lấy từ DB — KHÔNG lấy từ query param, nếu không thì Hub thành
     * open redirect ngay tại đây.
     */
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
