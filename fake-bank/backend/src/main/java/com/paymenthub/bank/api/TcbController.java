package com.paymenthub.bank.api;

import com.paymenthub.bank.api.dto.TcbPaymentInitRequest;
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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class TcbController {

    private static final String BANK_CODE = "TCB";

    private final BankTransactionService bankTransactionService;
    private final AccountAuthenticationService accountAuthenticationService;
    private final DebitService debitService;
    private final CancelService cancelService;
    private final BankProperties bankProperties;

    @PostMapping(path = "/tcb-gateway/payment/init",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> initPayment(@RequestBody TcbPaymentInitRequest request) {

        BankTransaction txn = bankTransactionService.openTransaction(BANK_CODE, new NewTransaction(
                request.requestId(), toMinorUnit(request.txnAmount(), request.ccy()), request.ccy(),
                request.merchantName(), request.notifyUrl(), request.redirectUrl()));

        return Map.of(
                "status", "SUCCESS",
                "code", "000",
                "message", "Khoi tao thanh cong",
                "txnRef", txn.getBankTxnRef(),
                "paymentUrl", bankProperties.pageUrl(
                        "/tcb-gateway/payment/checkout?ref=" + txn.getBankTxnRef()));
    }

    private static long toMinorUnit(String majorUnit, String currencyCode) {
        int fractionDigits = Math.max(Currency.getInstance(currencyCode).getDefaultFractionDigits(), 0);
        return new BigDecimal(majorUnit).movePointRight(fractionDigits).longValueExact();
    }

    @PostMapping("/tcb-gateway/payment/checkout/{bankTxnRef}")
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
                yield "tcb-pay";
            }
            case AccountCheck.Valid valid -> {
                try {
                    debitService.debit(valid.accountId(), txn);
                    yield "redirect:" + txn.getReturnUrl();
                } catch (DebitFailedException failed) {
                    model.addAttribute("notice", failed.getNotice());
                    yield "tcb-pay";
                }
            }
        };
    }

    @GetMapping("/tcb-gateway/payment/checkout")
    public String paymentPage(@RequestParam("ref") String bankTxnRef, Model model) {
        return bankTransactionService.findForPage(BANK_CODE, bankTxnRef)
                .map(txn -> {
                    model.addAttribute("txn", txn);
                    return "tcb-pay";
                })
                .orElse("transaction-not-found");
    }

    @PostMapping("/tcb-gateway/payment/checkout/{bankTxnRef}/cancel")
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
            return "tcb-pay";
        }
    }
}
