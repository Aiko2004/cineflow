package com.cineflow.payment.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// Webhook провайдера: POST /payment/webhooks/kaspi. ПУБЛИЧНЫЙ (permitAll и здесь, и на
// gateway): провайдер не может получить наш JWT. Подлинность вместо токена доказывает
// HMAC-подпись тела общим секретом (KaspiSignatureVerifier).
//
// Ответы: 200 — принято (в т.ч. повтор); 401 — подпись неверна; 400 — тело непригодно;
// 404 — платёж не найден. Реальные провайдеры повторяют доставку на любой не-2xx, поэтому
// 2xx отдаётся только тогда, когда исход действительно записан.
@RestController
@RequiredArgsConstructor
public class KaspiWebhookController {

    private final KaspiSignatureVerifier verifier;
    private final KaspiWebhookHandler handler;

    // Тело принимается как byte[]: подпись проверяется над сырыми байтами, ДО разбора JSON.
    @PostMapping("/payment/webhooks/kaspi")
    public ResponseEntity<Void> kaspi(
            @RequestHeader(name = KaspiSignatureVerifier.SIGNATURE_HEADER, required = false) String signature,
            @RequestBody byte[] body) {
        verifier.verify(body, signature);
        handler.handle(body);
        return ResponseEntity.ok().build();
    }
}
