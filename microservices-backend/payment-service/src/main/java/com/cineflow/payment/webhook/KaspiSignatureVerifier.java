package com.cineflow.payment.webhook;

import com.cineflow.payment.config.KaspiProperties;
import com.cineflow.payment.exception.InvalidWebhookSignatureException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

// Проверка подписи webhook: HMAC-SHA256 от СЫРЫХ байтов тела общим с провайдером секретом,
// в заголовке — hex.
//
// Почему от сырых байтов, а не от разобранного JSON: подпись считалась над тем, что провайдер
// отправил. Разбор и повторная сериализация меняют пробелы/порядок ключей — подпись бы не
// сошлась, а «нормализация перед проверкой» открывает место для расхождений, которыми
// пользуются атаки. Поэтому: сначала проверка над байтами, потом разбор.
@Component
public class KaspiSignatureVerifier {

    // Имя заголовка — протокол симулятора; у настоящего провайдера будет своё.
    public static final String SIGNATURE_HEADER = "X-Kaspi-Signature";
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public KaspiSignatureVerifier(KaspiProperties properties) {
        this.key = new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public void verify(byte[] body, String signatureHex) {
        if (signatureHex == null || signatureHex.isBlank()) {
            throw new InvalidWebhookSignatureException();
        }
        byte[] received;
        try {
            received = HexFormat.of().parseHex(signatureHex.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidWebhookSignatureException();
        }
        // MessageDigest.isEqual сравнивает за время, не зависящее от того, в каком байте первое
        // расхождение. Arrays.equals / String.equals выходят на первом несовпадении, и по
        // времени ответа подпись можно подбирать побайтно (timing attack).
        if (!MessageDigest.isEqual(hmac(body), received)) {
            throw new InvalidWebhookSignatureException();
        }
    }

    private byte[] hmac(byte[] body) {
        try {
            // Mac не потокобезопасен — новый экземпляр на вызов (дёшево по сравнению с запросом).
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 обязан быть в любой JRE; сюда попадаем только при сломанном окружении.
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
