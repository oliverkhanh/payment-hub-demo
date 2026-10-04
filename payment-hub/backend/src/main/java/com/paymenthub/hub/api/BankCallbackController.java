package com.paymenthub.hub.api;

import com.paymenthub.hub.api.dto.BankCallbackRequest;
import com.paymenthub.hub.domain.BankCallbackOutcome;
import com.paymenthub.hub.domain.BankCallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class BankCallbackController {

    private final BankCallbackService bankCallbackService;

    @PostMapping("/bank-callbacks/{bankCode}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String bankCode,
                                                       @RequestBody BankCallbackRequest callback) {

        BankCallbackOutcome outcome = bankCallbackService.apply(bankCode, callback);

        return ResponseEntity.status(httpStatusFor(outcome))
                .body(Map.of("event_id", String.valueOf(callback.eventId()),
                             "outcome", outcome.name()));
    }

    private static HttpStatus httpStatusFor(BankCallbackOutcome outcome) {
        return switch (outcome) {
            case CAPTURED, CANCELLED, ALREADY_SETTLED -> HttpStatus.OK;
            case ATTEMPT_NOT_FOUND                    -> HttpStatus.NOT_FOUND;
            case AMOUNT_MISMATCH, UNSUPPORTED_EVENT   -> HttpStatus.UNPROCESSABLE_ENTITY;
            case NOT_SETTLEABLE                       -> HttpStatus.CONFLICT;
        };
    }
}
