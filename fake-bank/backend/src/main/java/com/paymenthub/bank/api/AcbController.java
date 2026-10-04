package com.paymenthub.bank.api;

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
public class AcbController {

    private static final String BANK_CODE = "ACB";

    private final BankTransactionService bankTransactionService;
    private final AccountAuthenticationService accountAuthenticationService;
    private final DebitService debitService;
    private final CancelService cancelService;
    private final BankProperties bankProperties;

    @PostMapping(path = "/acb/api/v2/pay-session",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> openPaySession(
            @RequestParam("AMT") long amt,
            @RequestParam("CUR_CODE") String curCode,
            @RequestParam("MER_NAME") String merName,
            @RequestParam("REF_NO") String refNo,
            @RequestParam("NOTIFY_URL") String notifyUrl,
            @RequestParam("RET_URL") String retUrl) {

        BankTransaction txn = bankTransactionService.openTransaction(BANK_CODE, new NewTransaction(
                refNo, amt, curCode, merName, notifyUrl, retUrl));

        return Map.of(
                "error_no", 0,
                "error_desc", "THANH CONG",
                "txn_id", txn.getBankTxnRef(),
                "pay_url", bankProperties.pageUrl("/acb/pay-session/" + txn.getBankTxnRef()));
    }

    @PostMapping("/acb/pay-session/{bankTxnRef}")
    public String authenticate(@PathVariable String bankTxnRef,
                               @RequestParam("accountNumber") String accountNumber,
                               @RequestParam("password") String password,
                               Model model) {

        BankTransaction txn = bankTransactionService.findForPage(BANK_CODE, bankTxnRef).orElse(null);
        if (txn == null) {
            return "transaction-not-found";
        }

        AccountCheck check = accountAuthenticationService.authenticate(BANK_CODE, accountNumber, password);
        model.addAttribute("txn", txn);
        model.addAttribute("enteredAccount", accountNumber);

        return switch (check) {
            case AccountCheck.Rejected rejected -> {
                model.addAttribute("notice", rejected.notice());
                yield "acb-pay";
            }
            case AccountCheck.Valid valid -> {
                try {
                    debitService.debit(valid.accountId(), txn);
                    yield "redirect:" + txn.getReturnUrl();
                } catch (DebitFailedException failed) {
                    model.addAttribute("notice", failed.getNotice());
                    yield "acb-pay";
                }
            }
        };
    }

    @GetMapping("/acb/pay-session/{bankTxnRef}")
    public String paymentPage(@PathVariable String bankTxnRef, Model model) {
        return bankTransactionService.findForPage(BANK_CODE, bankTxnRef)
                .map(txn -> {
                    model.addAttribute("txn", txn);
                    return "acb-pay";
                })
                .orElse("transaction-not-found");
    }

    @PostMapping("/acb/pay-session/{bankTxnRef}/cancel")
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
            return "acb-pay";
        }
    }
}
