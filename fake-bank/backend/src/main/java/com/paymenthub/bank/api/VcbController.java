package com.paymenthub.bank.api;

import com.paymenthub.bank.api.dto.VcbCreateTransactionRequest;
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
public class VcbController {

    private static final String BANK_CODE = "VCB";

    private final BankTransactionService bankTransactionService;
    private final AccountAuthenticationService accountAuthenticationService;
    private final DebitService debitService;
    private final CancelService cancelService;
    private final BankProperties bankProperties;

    @PostMapping(path = "/banks/VCB/v1/transactions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> createTransaction(
            @RequestBody VcbCreateTransactionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        BankTransaction txn = bankTransactionService.openTransaction(BANK_CODE, new NewTransaction(
                idempotencyKey, request.amount(), request.currency(),
                request.merchantName(), request.callbackUrl(), request.returnUrl()));

        return Map.of(
                "result_code", "00",
                "message", "OK",
                "bank_txn_id", txn.getBankTxnRef(),
                "redirect_url", bankProperties.pageUrl("/banks/VCB/pay/" + txn.getBankTxnRef()));
    }

    @PostMapping("/banks/VCB/pay/{bankTxnRef}")
    public String authenticate(@PathVariable String bankTxnRef,
                               @RequestParam("username") String username,
                               @RequestParam("password") String password,
                               Model model) {

        BankTransaction txn = bankTransactionService.findForPage(BANK_CODE, bankTxnRef).orElse(null);
        if (txn == null) {
            return "transaction-not-found";
        }

        AccountCheck check = accountAuthenticationService.authenticate(BANK_CODE, username, password);
        model.addAttribute("txn", txn);
        model.addAttribute("enteredAccount", username);

        return switch (check) {
            case AccountCheck.Rejected rejected -> {
                model.addAttribute("notice", rejected.notice());
                yield "vcb-pay";
            }
            case AccountCheck.Valid valid -> {
                try {
                    debitService.debit(valid.accountId(), txn);
                    yield "redirect:" + txn.getReturnUrl();
                } catch (DebitFailedException failed) {
                    model.addAttribute("notice", failed.getNotice());
                    yield "vcb-pay";
                }
            }
        };
    }

    @GetMapping("/banks/VCB/pay/{bankTxnRef}")
    public String paymentPage(@PathVariable String bankTxnRef, Model model) {
        return bankTransactionService.findForPage(BANK_CODE, bankTxnRef)
                .map(txn -> {
                    model.addAttribute("txn", txn);
                    return "vcb-pay";
                })
                .orElse("transaction-not-found");
    }

    @PostMapping("/banks/VCB/pay/{bankTxnRef}/cancel")
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
            return "vcb-pay";
        }
    }
}
