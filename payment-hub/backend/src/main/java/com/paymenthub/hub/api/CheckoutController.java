package com.paymenthub.hub.api;

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

    public CheckoutController(PaymentService paymentService) {
        this.paymentService = paymentService;
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
        // KHÔNG đưa ra view: payment.id, merchant secret, danh sách payment khác.
        return "checkout";
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
