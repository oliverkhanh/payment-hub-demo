package com.paymenthub.bank.api;

import com.paymenthub.bank.api.dto.BidvCreateOrderRequest;
import com.paymenthub.bank.config.BankProperties;
import com.paymenthub.bank.domain.AccountAuthenticationService;
import com.paymenthub.bank.domain.AccountCheck;
import com.paymenthub.bank.domain.BankTransaction;
import com.paymenthub.bank.domain.BankTransactionService;
import com.paymenthub.bank.domain.CancelFailedException;
import com.paymenthub.bank.domain.CancelService;
import com.paymenthub.bank.domain.DebitFailedException;
import com.paymenthub.bank.domain.DebitService;
import com.paymenthub.bank.domain.NewTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class BidvController {

    private static final String BANK_CODE = "BIDV";

    private final BankTransactionService bankTransactionService;
    private final AccountAuthenticationService accountAuthenticationService;
    private final DebitService debitService;
    private final CancelService cancelService;
    private final BankProperties bankProperties;

    @Value("${bank.bidv.response-delay-ms:5000}")
    private long responseDelayMs;

    @PostMapping(path = "/bidv/openapi/v1/orders",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> createOrder(@RequestBody BidvCreateOrderRequest request) {

        BankTransaction txn = bankTransactionService.openTransaction(BANK_CODE, new NewTransaction(
                request.orderRef(), request.orderAmount(), request.orderCurrency(),
                request.merchant(), request.ipnUrl(), request.returnUrl()));

        sleepBeforeAnswering(txn);

        return Map.of(
                "rc", "00",
                "rd", "Success",
                "orderId", txn.getBankTxnRef(),
                "payUrl", bankProperties.pageUrl("/bidv/order/" + txn.getBankTxnRef()));
    }

    private void sleepBeforeAnswering(BankTransaction txn) {
        if (responseDelayMs <= 0) {
            return;
        }
        log.warn("[BIDV] giao dịch {} ĐÃ TẠO, nhưng trả lời chậm {}ms — Hub sẽ timeout",
                txn.getBankTxnRef(), responseDelayMs);
        try {
            Thread.sleep(responseDelayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @PostMapping("/bidv/order/{bankTxnRef}")
    public String authenticate(@PathVariable String bankTxnRef,
                               @RequestParam("accountNumber") String accountNumber,
                               @RequestParam("pin") String pin,
                               Model model) {

        BankTransaction txn = bankTransactionService.findForPage(BANK_CODE, bankTxnRef).orElse(null);
        if (txn == null) {
            return "transaction-not-found";
        }

        AccountCheck check = accountAuthenticationService.authenticate(BANK_CODE, accountNumber, pin);
        model.addAttribute("txn", txn);
        model.addAttribute("enteredAccount", accountNumber);

        return switch (check) {
            case AccountCheck.Rejected rejected -> {
                model.addAttribute("notice", rejected.notice());
                yield "bidv-pay";
            }
            case AccountCheck.Valid valid -> {
                try {
                    debitService.debit(valid.accountId(), txn);
                    yield "redirect:" + txn.getReturnUrl();
                } catch (DebitFailedException failed) {
                    model.addAttribute("notice", failed.getNotice());
                    yield "bidv-pay";
                }
            }
        };
    }

    @GetMapping("/bidv/order/{bankTxnRef}")
    public String paymentPage(@PathVariable String bankTxnRef, Model model) {
        return bankTransactionService.findForPage(BANK_CODE, bankTxnRef)
                .map(txn -> {
                    model.addAttribute("txn", txn);
                    return "bidv-pay";
                })
                .orElse("transaction-not-found");
    }

    @PostMapping("/bidv/order/{bankTxnRef}/cancel")
    public String cancel(@PathVariable String bankTxnRef, Model model) {
        BankTransaction txn = bankTransactionService.findForPage(BANK_CODE, bankTxnRef).orElse(null);
        if (txn == null) {
            return "transaction-not-found";
        }

        try {
            cancelService.cancel(txn);
            return "redirect:" + txn.getReturnUrl();
        } catch (CancelFailedException failed) {
            model.addAttribute("txn", txn);
            model.addAttribute("notice", failed.getNotice());
            return "bidv-pay";
        }
    }
}
