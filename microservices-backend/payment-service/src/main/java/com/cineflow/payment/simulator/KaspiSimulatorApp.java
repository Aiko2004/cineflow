package com.cineflow.payment.simulator;

import com.cineflow.payment.config.KaspiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// «Приложение Kaspi» в симуляторе: пользователь оплачивает или отклоняет счёт, провайдер
// уведомляет нас HTTP-webhook'ом с HMAC-подписью. Webhook идёт по настоящему HTTP на
// /payment/webhooks/kaspi — путь обработки тот же, что был бы с реальным провайдером.
//
// delaySeconds — задержка ДОСТАВКИ webhook, а не оплаты: деньги списываются в момент «нажатия»
// (статус счёта PAID сразу), уведомление приходит позже. Так у реальных провайдеров и бывает —
// оплата атомарна у них, переменна задержка уведомления. Этим воспроизводятся опоздавшая
// оплата (PAYMENT_DESIGN.md §3, сценарий 3) и потерянный webhook: если задержка больше, чем
// до истечения счёта, платёж первой найдёт сверка, а опоздавший webhook получит «уже PAID».
//
// Повторной доставки webhook при ошибке в симуляторе НЕТ (реальные провайдеры повторяют на
// не-2xx). Потерю уведомления закрывает сверка — это и есть её назначение.
@Component
@Profile("kaspi-sim")
@Slf4j
public class KaspiSimulatorApp {

    // Протокол симулятора. Подпись считается здесь своим кодом, а не через KaspiSignatureVerifier
    // сервиса: провайдер — независимая сторона. Общий код подписи сделал бы ошибку в нём
    // симметричной (подпись и проверка ошиблись бы одинаково) и невидимой для тестов.
    private static final String SIGNATURE_HEADER = "X-Kaspi-Signature";
    private static final String WEBHOOK_URL_PROPERTY = "cineflow.payment.kaspi.simulator.webhook-url";

    private final JdbcClient jdbc;
    private final TaskScheduler scheduler;
    private final Environment environment;
    private final JsonMapper jsonMapper;
    private final SecretKeySpec key;
    private final RestClient restClient;

    public KaspiSimulatorApp(JdbcClient jdbc, TaskScheduler scheduler, Environment environment,
                             JsonMapper jsonMapper, KaspiProperties properties) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.environment = environment;
        this.jsonMapper = jsonMapper;
        this.key = new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        // Таймауты обязательны: webhook отправляется из потока планировщика, общего с relay и
        // фоновыми задачами. Без таймаута зависший приёмник занял бы этот поток навсегда.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    // Пользователь оплатил счёт. Webhook PAID уйдёт через delaySeconds.
    public SimInvoiceActionResponse pay(String invoiceId, int delaySeconds) {
        return settle(invoiceId, "PAID", delaySeconds);
    }

    // Пользователь отклонил счёт. Webhook DECLINED уходит сразу.
    public SimInvoiceActionResponse decline(String invoiceId) {
        return settle(invoiceId, "DECLINED", 0);
    }

    // Без @Transactional: единственный UPDATE коммитится сам (autocommit). Webhook планируется
    // после него — при delaySeconds=0 он не может обогнать собственный коммит.
    private SimInvoiceActionResponse settle(String invoiceId, String outcome, int delaySeconds) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated = jdbc.sql("""
                        UPDATE sim_invoices SET status = :outcome, updated_at = :now
                         WHERE provider_invoice_id = :id AND status = 'PENDING' AND expires_at > :now
                        """)
                .param("outcome", outcome)
                .param("now", now)
                .param("id", invoiceId)
                .update();
        if (updated == 0) {
            throw rejection(invoiceId);
        }

        UUID paymentId = jdbc.sql("SELECT payment_id FROM sim_invoices WHERE provider_invoice_id = :id")
                .param("id", invoiceId)
                .query(UUID.class)
                .single();
        Instant webhookAt = Instant.now().plusSeconds(delaySeconds);
        scheduler.schedule(() -> sendWebhook(invoiceId, paymentId, outcome), webhookAt);
        return new SimInvoiceActionResponse(invoiceId, outcome, webhookAt);
    }

    private SimulatorRequestException rejection(String invoiceId) {
        return jdbc.sql("SELECT status, expires_at FROM sim_invoices WHERE provider_invoice_id = :id")
                .param("id", invoiceId)
                .query((rs, rowNum) -> {
                    String status = rs.getString("status");
                    boolean expired = rs.getObject("expires_at", OffsetDateTime.class).isBefore(OffsetDateTime.now());
                    String reason = "PENDING".equals(status) && expired ? "expired" : "already " + status;
                    return new SimulatorRequestException(HttpStatus.CONFLICT, "Invoice " + invoiceId + " is " + reason);
                })
                .optional()
                .orElseGet(() -> new SimulatorRequestException(HttpStatus.NOT_FOUND, "Unknown invoice " + invoiceId));
    }

    private void sendWebhook(String invoiceId, UUID paymentId, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoiceId", invoiceId);
        payload.put("paymentId", paymentId);
        payload.put("status", status);
        payload.put("occurredAt", Instant.now());
        byte[] body = jsonMapper.writeValueAsBytes(payload);

        // URL читается в момент отправки, а не при создании бина: в тестах с RANDOM_PORT он
        // содержит ${local.server.port}, который появляется только после старта веб-сервера.
        String url = environment.getRequiredProperty(WEBHOOK_URL_PROPERTY);
        try {
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SIGNATURE_HEADER, HexFormat.of().formatHex(sign(body)))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Simulator: webhook {} for invoice {} delivered", status, invoiceId);
        } catch (RestClientException e) {
            log.warn("Simulator: webhook {} for invoice {} NOT delivered (reconciliation will catch up): {}",
                    status, invoiceId, e.toString());
        }
    }

    private byte[] sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
