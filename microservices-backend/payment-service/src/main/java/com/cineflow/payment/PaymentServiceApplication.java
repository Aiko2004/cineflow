package com.cineflow.payment;

import com.cineflow.payment.config.CommandsProperties;
import com.cineflow.payment.config.JobsProperties;
import com.cineflow.payment.config.KaspiProperties;
import com.cineflow.payment.config.OutboxProperties;
import com.cineflow.payment.config.PaymentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // outbox relay, сверка, повтор возвратов, отложенные webhook симулятора
@EnableConfigurationProperties({PaymentProperties.class, OutboxProperties.class, KaspiProperties.class,
        JobsProperties.class, CommandsProperties.class})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
