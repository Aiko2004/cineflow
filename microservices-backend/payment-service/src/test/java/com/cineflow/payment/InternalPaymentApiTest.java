package com.cineflow.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

// HTTP-слой целиком (security + валидация + контроллер + БД), но без сети.
@AutoConfigureMockMvc
class InternalPaymentApiTest extends AbstractPaymentIntegrationTest {

    @Autowired MockMvc mockMvc;

    private static final String USER = "0199c4f2-0000-7000-8000-0000000000aa";

    private String body(UUID bookingId, String amount, String phone) {
        return """
                {"bookingId":"%s","amount":%s,"phone":"%s"}
                """.formatted(bookingId, amount, phone);
    }

    @Test
    void withoutTokenIs401() throws Exception {
        mockMvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "1500.00", "+77011234567")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsInvoiceAndUserIdComesFromSub() throws Exception {
        UUID bookingId = UUID.randomUUID();

        mockMvc.perform(post("/internal/payments").with(jwt().jwt(j -> j.subject(USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(bookingId, "1500.00", "+77011234567")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").exists())
                .andExpect(jsonPath("$.providerInvoiceId").value(org.hamcrest.Matchers.startsWith("SIM-")))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id::text FROM payments WHERE booking_id = ?", String.class, bookingId))
                .isEqualTo(USER);
    }

    @Test
    void userIdInBodyIsIgnored() throws Exception {
        UUID bookingId = UUID.randomUUID();
        String forged = """
                {"bookingId":"%s","amount":10.00,"phone":"+77011234567","userId":"%s"}
                """.formatted(bookingId, UUID.randomUUID());

        mockMvc.perform(post("/internal/payments").with(jwt().jwt(j -> j.subject(USER)))
                        .contentType(MediaType.APPLICATION_JSON).content(forged))
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id::text FROM payments WHERE booking_id = ?", String.class, bookingId))
                .isEqualTo(USER);
    }

    @Test
    void invalidInputIs400() throws Exception {
        for (String bad : new String[]{
                body(UUID.randomUUID(), "0", "+77011234567"),            // сумма не положительная
                body(UUID.randomUUID(), "10.123", "+77011234567"),       // больше 2 знаков
                body(UUID.randomUUID(), "1500.00", "not-a-phone")}) {    // телефон
            mockMvc.perform(post("/internal/payments").with(jwt().jwt(j -> j.subject(USER)))
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void repeatReturnsSamePaymentAndConflictOnDifferentAmount() throws Exception {
        UUID bookingId = UUID.randomUUID();
        String first = mockMvc.perform(post("/internal/payments").with(jwt().jwt(j -> j.subject(USER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(bookingId, "1500.00", "+77011234567")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/internal/payments").with(jwt().jwt(j -> j.subject(USER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(bookingId, "1500.00", "+77011234567")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        JsonNode a = jsonMapper.readTree(first);
        JsonNode b = jsonMapper.readTree(second);
        assertThat(b.get("paymentId")).isEqualTo(a.get("paymentId"));

        mockMvc.perform(post("/internal/payments").with(jwt().jwt(j -> j.subject(USER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(bookingId, "1.00", "+77011234567")))
                .andExpect(status().isConflict());
    }

    @Test
    void ownerSeesPaymentAndStrangerGets404() throws Exception {
        UUID bookingId = UUID.randomUUID();
        String created = mockMvc.perform(post("/internal/payments").with(jwt().jwt(j -> j.subject(USER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(bookingId, "1500.00", "+77011234567")))
                .andReturn().getResponse().getContentAsString();
        String paymentId = jsonMapper.readTree(created).get("paymentId").asString();

        mockMvc.perform(get("/payment/" + paymentId).with(jwt().jwt(j -> j.subject(USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.phone").doesNotExist());

        mockMvc.perform(get("/payment/" + paymentId).with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
    }
}
