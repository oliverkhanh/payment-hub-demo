package com.paymenthub.hub.api;

import com.paymenthub.hub.domain.Payment;
import com.paymenthub.hub.infra.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentStatusWatch {

    private record Waiter(String seenStatus, DeferredResult<PaymentStatusView> result) { }

    private final Map<String, List<Waiter>> waitersByToken = new ConcurrentHashMap<>();

    private final PaymentRepository paymentRepository;

    public void register(String checkoutToken, String seenStatus,
                         DeferredResult<PaymentStatusView> result) {
        Waiter waiter = new Waiter(seenStatus, result);
        waitersByToken.computeIfAbsent(checkoutToken, token -> new CopyOnWriteArrayList<>())
                .add(waiter);

        result.onCompletion(() -> forget(checkoutToken, waiter));
        result.onTimeout(() -> forget(checkoutToken, waiter));
    }

    public int waitingCount() {
        return waitersByToken.values().stream().mapToInt(List::size).sum();
    }

    @Scheduled(fixedDelayString = "${hub.checkout.status-watch-interval-ms:500}")
    @Transactional(readOnly = true)
    public void releaseChangedStatuses() {
        if (waitersByToken.isEmpty()) {
            return;
        }

        for (String checkoutToken : waitersByToken.keySet()) {
            Payment payment = paymentRepository.findByCheckoutToken(checkoutToken).orElse(null);
            if (payment == null) {
                continue;
            }
            releaseIfChanged(checkoutToken, payment.getStatus().name());
        }
    }

    private void releaseIfChanged(String checkoutToken, String currentStatus) {
        List<Waiter> waiters = waitersByToken.get(checkoutToken);
        if (waiters == null) {
            return;
        }

        for (Waiter waiter : waiters) {
            if (currentStatus.equals(waiter.seenStatus())) {
                continue;
            }
            log.info("Long poll nhả: {} đổi {} -> {}", checkoutToken, waiter.seenStatus(),
                    currentStatus);
            waiter.result().setResult(new PaymentStatusView(currentStatus));
        }
    }

    private void forget(String checkoutToken, Waiter waiter) {
        List<Waiter> waiters = waitersByToken.get(checkoutToken);
        if (waiters == null) {
            return;
        }
        waiters.remove(waiter);
        if (waiters.isEmpty()) {
            waitersByToken.remove(checkoutToken);
        }
    }
}
