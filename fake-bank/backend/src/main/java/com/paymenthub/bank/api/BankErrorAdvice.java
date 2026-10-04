package com.paymenthub.bank.api;

import com.paymenthub.bank.domain.BankSystemFailureException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@Slf4j
@ControllerAdvice
public class BankErrorAdvice {

    @ExceptionHandler(BankSystemFailureException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleSystemFailure(BankSystemFailureException failure,
                                      HttpServletRequest request, Model model) {

        log.error("[{}] core banking lỗi khi xử lý {}: {}",
                failure.getBankCode(), failure.getUsername(), failure.getMessage());

        model.addAttribute("backUrl", request.getRequestURI());
        model.addAttribute("bankTxnRef", lastPathSegment(request.getRequestURI()));
        return "bank-error";
    }

    private static String lastPathSegment(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? path : path.substring(lastSlash + 1);
    }
}
