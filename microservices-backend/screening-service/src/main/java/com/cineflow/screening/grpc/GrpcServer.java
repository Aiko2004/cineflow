package com.cineflow.screening.grpc;

import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

// Поднимает gRPC-сервер рядом с HTTP-сервером сервиса, на отдельном порту.
// gRPC не живёт внутри Tomcat: это собственный сервер (HTTP/2) со своим портом.
//
// SmartLifecycle привязывает старт/стоп к жизненному циклу Spring-контекста.
//
// ── Почему порт 9083 без аутентификации — осознанное решение (BOOKING_DESIGN.md §5) ──
// Это ВНУТРЕННИЙ порт booking→screening: он не проксируется через api-gateway (тот работает
// только с HTTP) и не публикуется в docker-compose. Чтобы «внутренний» было ФАКТОМ, а не
// допущением, по умолчанию биндимся на loopback (127.0.0.1) — сервер недоступен из LAN,
// его видит только co-located booking-service. Для контейнеров host переопределяется
// (GRPC_HOST=0.0.0.0), и изоляция обеспечивается тем, что порт не публикуется наружу.
// Если вызов когда-нибудь пересечёт границу доверия — заменить InsecureServerCredentials
// на TlsServerCredentials (mTLS); шов ровно здесь.
@Component
@Slf4j
public class GrpcServer implements SmartLifecycle {

    private final ScreeningGrpcService screeningGrpcService;
    private final String host;
    private final int port;

    private Server server;
    private volatile boolean running = false;

    public GrpcServer(ScreeningGrpcService screeningGrpcService,
                      @Value("${grpc.server.host:127.0.0.1}") String host,
                      @Value("${grpc.server.port:9083}") int port) {
        this.screeningGrpcService = screeningGrpcService;
        this.host = host;
        this.port = port;
    }

    @Override
    public void start() {
        try {
            // forAddress с явным host — в отличие от forPort, который слушает все интерфейсы (0.0.0.0).
            server = NettyServerBuilder.forAddress(new InetSocketAddress(host, port),
                            InsecureServerCredentials.create())
                    .addService(screeningGrpcService)
                    .build()
                    .start();
            running = true;
            log.info("gRPC server started on {}:{}", host, port);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gRPC server on " + host + ":" + port, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            try {
                server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
        }
        running = false;
        log.info("gRPC server stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
