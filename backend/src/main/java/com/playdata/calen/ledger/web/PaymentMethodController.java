package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.dto.PaymentMethodRequest;
import com.playdata.calen.ledger.dto.PaymentMethodResponse;
import com.playdata.calen.ledger.service.PaymentMethodService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment-methods")
@RequiredArgsConstructor
public class PaymentMethodController {

    private final PaymentMethodService paymentMethodService;

    @GetMapping
    public List<PaymentMethodResponse> getPaymentMethods(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return paymentMethodService.getPaymentMethods(currentUser.userId());
    }

    @PostMapping
    public PaymentMethodResponse createPaymentMethod(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody PaymentMethodRequest request
    ) {
        return paymentMethodService.create(currentUser.userId(), request);
    }

    @DeleteMapping("/{id}")
    public void deactivate(@AuthenticationPrincipal AppUserPrincipal currentUser, @PathVariable Long id) {
        paymentMethodService.deactivate(currentUser.userId(), id);
    }
}
