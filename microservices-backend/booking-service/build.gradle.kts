plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "booking-service"

// gRPC runtime канала. 1.83.1 — совпадает с grpc-core, который тянет Spring Boot BOM
// (иначе AbstractMethodError из-за смешения версий grpc-netty-shaded и grpc-core).
val grpcVersion = "1.83.1"

dependencies {
    // Контракты: enum SeatType + сгенерированные gRPC-стабы screening.proto.
    implementation(project(":contracts"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Защита ресурсного сервиса (AUTH_DESIGN.md §8).
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Resilience4j (CB + bulkhead + retry) — оборачивает и Feign, и gRPC-вызовы.
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // gRPC-рантайм клиента: канал поверх Netty (shaded — свой netty, без конфликтов версий).
    // Стабы и protobuf-java приходят транзитивно из :contracts (api).
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")

    implementation("com.github.f4b6a3:uuid-creator:6.0.0")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}
