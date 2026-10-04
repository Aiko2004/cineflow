plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "auth-service"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Redis: OTP-коды (Hash с TTL) и счётчики rate limit.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Подпись RS256 и генерация JWKS. Версия управляется Spring Boot BOM
    // (spring-boot-dependencies объявляет nimbus-jose-jwt.version), поэтому без явной версии.
    // auth-service НЕ resource-server: он выпускает токены, а не проверяет их,
    // поэтому spring-security сюда не подключается — только чистый Nimbus.
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")

    // UUIDv7 — time-ordered UUID, дружелюбен к индексам (см. AUTH_DESIGN.md §"Про версию UUID").
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
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
