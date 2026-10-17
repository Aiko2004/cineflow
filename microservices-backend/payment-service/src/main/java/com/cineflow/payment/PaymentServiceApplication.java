package com.cineflow.payment;

import com.cineflow.payment.config.OutboxProperties;
import com.cineflow.payment.config.PaymentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // outbox relay (и дальше — сверка, повтор возвратов)
@EnableConfigurationProperties({PaymentProperties.class, OutboxProperties.class})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
