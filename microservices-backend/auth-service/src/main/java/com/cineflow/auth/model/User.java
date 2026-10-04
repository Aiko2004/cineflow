package com.cineflow.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

// Аккаунт. Колонки с паролем нет вообще — вход только по одноразовому коду
// (нечего утекать, нечего перебирать). См. AUTH_DESIGN.md §1.
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    // UUIDv7 присваивается приложением (не @GeneratedValue) — так же, как в screening-service.
    // Тип колонки — нативный UUID (16 байт), не VARCHAR(36).
    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    // Смена/вход по телефону появится позже — пока nullable.
    @Column(unique = true, length = 20)
    private String phone;

    @Column(nullable = false, length = 32)
    private String role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
