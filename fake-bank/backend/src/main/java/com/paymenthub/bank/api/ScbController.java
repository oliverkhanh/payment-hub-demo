package com.paymenthub.bank.api;

import com.paymenthub.bank.api.dto.ScbPaymentOrderRequest;
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

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ScbController {

    private static final String BANK_CODE = "SCB";

    private final BankTransactionService bankTransactionService;
    private final AccountAuthenticationService accountAuthenticationService;
    private final DebitService debitService;
    private final CancelService cancelService;
    private final BankProperties bankProperties;

    @PostMapping(path = "/scb/openapi/v1/payment-orders",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> createPaymentOrder(@RequestBody ScbPaymentOrderRequest request) {

        var data = request.data();
        BankTransaction txn = bankTransactionService.openTransaction(BANK_CODE, new NewTransaction(
                request.header().requestId(), Long.parseLong(data.amt()), data.ccy(),
                data.merchantNm(), data.callbackUri(), data.returnUri()));

        return Map.of(
                "Header", Map.of("ResponseCode", "000", "ResponseDesc", "Success"),
                "Data", Map.of(
                        "TransactionID", txn.getBankTxnRef(),
                        "PaymentURL", bankProperties.pageUrl(
                                "/scb/openapi/payment/" + txn.getBankTxnRef())));
    }

    @PostMapping("/scb/openapi/payment/{bankTxnRef}")
    public String authenticate(@PathVariable String bankTxnRef,
                               @RequestParam("cif") String cif,
                               @RequestParam("pin") String pin,
                               Model model) {

        BankTransaction txn = bankTransactionService.findForPage(BANK_CODE, bankTxnRef).orElse(null);
        if (txn == null) {
            return "transaction-not-found";
        }

        AccountCheck check = accountAuthenticationService.authenticate(BANK_CODE, cif, pin);
        model.addAttribute("txn", txn);
        model.addAttribute("enteredAccount", cif);

        return switch (check) {
            case AccountCheck.Rejected rejected -> {
                model.addAttribute("notice", rejected.notice());
                yield "scb-pay";
            }
            case AccountCheck.Valid valid -> {
                try {
                    debitService.debit(valid.accountId(), txn);
                    yield "redirect:" + txn.getReturnUrl();
                } catch (DebitFailedException failed) {
                    model.addAttribute("notice", failed.getNotice());
                    yield "scb-pay";
                }
            }
        };
    }

    @GetMapping("/scb/openapi/payment/{bankTxnRef}")
    public String paymentPage(@PathVariable String bankTxnRef, Model model) {
        return bankTransactionService.findForPage(BANK_CODE, bankTxnRef)
                .map(txn -> {
                    model.addAttribute("txn", txn);
                    return "scb-pay";
                })
                .orElse("transaction-not-found");
    }

    @PostMapping("/scb/openapi/payment/{bankTxnRef}/cancel")
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
            return "scb-pay";
        }
    }
}
