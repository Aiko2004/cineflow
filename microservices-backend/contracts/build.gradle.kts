import com.google.protobuf.gradle.id

// contracts — общий модуль межсервисных контрактов.
//
// Сюда попадает ТОЛЬКО то, что физически пересекает границу сервиса:
// enum'ы, DTO событий/сообщений и .proto-контракты gRPC. Никаких @Entity,
// @Component, репозиториев, бизнес-логики — иначе распределённый монолит.
//
// Плагин Spring Boot здесь не применяется: это библиотека, а не приложение.
//
// gRPC: .proto лежит в src/main/proto, protobuf-плагин генерирует из него Java —
// сообщения (protobuf-java) и стабы клиента/сервера (protoc-gen-grpc-java).
// Сгенерированный код — такой же контракт, как enum'ы: его импортируют и сервер
// (screening-service), и клиент (booking-service). java-library + api(...) нужны,
// чтобы grpc-классы и их зависимости (grpc-stub, protobuf-java) были видны потребителям.
plugins {
    `java-library`
    id("com.google.protobuf")
}

// Версии gRPC/protobuf держим здесь: contracts — единственный владелец .proto.
//
// grpcVersion выровнен на 1.83.1 — ту же версию, до которой Spring Boot BOM поднимает
// io.grpc:* в сервисах. Раньше здесь было 1.68.1: сгенерированный код компилировался
// против 1.68.1, а исполнялся на 1.83.1 — та же категория расхождения, что дала
// AbstractMethodError с grpc-netty-shaded. contracts BOM не применяет, поэтому версию
// фиксируем явно и держим равной BOM-версии.
//
// protobuf оставляем на 4.28.3: grpc 1.83.1 совместим с protobuf 4.x (его grpc-protobuf
// тянет protobuf-java 3.25.x, мы поднимаем до 4.28.3). В рантайме сервисов BOM поднимает
// protobuf-java до 4.35.1 — сгенерированный protoc 4.28.3 код обратно совместим с ним
// (protobuf гарантирует forward-compat сгенерированного кода к более новому рантайму).
val grpcVersion = "1.83.1"
val protobufVersion = "4.36.2"

dependencies {
    // compileOnly: Jackson-аннотации нужны на компиляции, рантайм есть у потребителей.
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.21")

    // api: сгенерированный код ссылается на эти классы, значит они должны попасть
    // на compile-classpath потребителей contracts (транзитивно).
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    api("com.google.protobuf:protobuf-java:$protobufVersion")

    // grpc-java генерирует классы с аннотацией javax.annotation.Generated —
    // на новых JDK её нет в стандартной библиотеке, добавляем только на компиляцию.
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        // Плагин protoc, генерирующий gRPC-стабы (ServiceGrpc с ImplBase и newBlockingStub).
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc") { }
            }
        }
    }
}
