package com.cineflow.booking.config;

import com.cineflow.contracts.grpc.screening.ScreeningServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// gRPC-канал и blocking-стаб к screening-service.
//
// Отличие от Feign на уровне конфигурации: Feign-клиент — это интерфейс с аннотациями,
// прокси создаёт Spring Cloud OpenFeign автоматически. Здесь мы вручную поднимаем
// ManagedChannel (долгоживущее HTTP/2-соединение с пулом) и строим из него стаб.
// Канал создаётся один раз и переиспользуется — не открываем соединение на каждый вызов.
@Configuration
public class GrpcClientConfig {

    // destroyMethod: канал закрывается при остановке контекста (освобождает соединения/потоки).
    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel screeningChannel(
            @Value("${grpc.client.screening.host}") String host,
            @Value("${grpc.client.screening.port}") int port) {
        // usePlaintext — без TLS (внутренний трафик).
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }

    // Blocking-стаб: синхронный вызов, как обычный метод (в отличие от async/streaming стабов).
    @Bean
    public ScreeningServiceGrpc.ScreeningServiceBlockingStub screeningStub(ManagedChannel screeningChannel) {
        return ScreeningServiceGrpc.newBlockingStub(screeningChannel);
    }
}
