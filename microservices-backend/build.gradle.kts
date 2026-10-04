// Корневая конфигурация multi-module сборки.
//
// Версии плагинов объявляются ЗДЕСЬ один раз с `apply false` — модули потом
// применяют их без указания версии. Это устраняет конфликт вида
// "plugin already on the classpath with a different version".
//
// Spring Boot намеренно НЕ применяется ко всем subprojects скопом: модуль
// `contracts` — это библиотека, а не приложение, у него нет main-класса,
// и задача bootJar на нём падает. Поэтому каждый модуль сам объявляет,
// приложение он или библиотека.

plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    // protobuf-плагин: компилирует .proto в Java (сообщения + gRPC-стабы).
    // Применяется только в contracts (там лежит .proto); здесь объявлен один раз с apply false.
    id("com.google.protobuf") version "0.9.4" apply false
}

allprojects {
    group = "com.cineflow"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extra["springCloudVersion"] = "2025.1.3"

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
