plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "screening-service"

dependencies {
    implementation(project(":contracts"))

    // gRPC-сервер: реализуем ScreeningService из screening.proto (стабы приходят из :contracts).
    // grpc-netty-shaded — транспорт сервера (свой шейдед netty, не конфликтует с Tomcat/Reactor).
    // Версию НЕ фиксируем ниже BOM: Spring Boot BOM тянет grpc-core 1.83.1, и старый
    // grpc-netty-shaded дал бы AbstractMethodError (несовпадение внутреннего интерфейса сервера).
    implementation("io.grpc:grpc-netty-shaded:1.83.1")

    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // Ресурсный сервер: проверяет JWT публичным ключом из JWKS auth-service (AUTH_DESIGN.md §8).
    // Защищает только HTTP-слой; gRPC-сервер (9083) — внутренний, не затрагивается.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Resilience4j: circuit breaker + bulkhead + annotation support.
    // Версия управляется через resilience4j-bom, который импортирует Spring Cloud BOM.
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    // AOP нужен для работы @CircuitBreaker / @Bulkhead — они реализованы через Spring AOP.
    // В Spring Boot 4 стартер переименован: spring-boot-starter-aop → spring-boot-starter-aspectj.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // UUIDv7 — time-ordered UUID: глобально уникален без координации,
    // но в отличие от UUIDv4 монотонно растёт, поэтому дружелюбен к индексам.
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-data-mongodb-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mongodb")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}
