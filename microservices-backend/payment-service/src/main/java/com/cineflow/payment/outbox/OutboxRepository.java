package com.cineflow.payment.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

// Доступ к таблице outbox на JdbcClient, а не JPA-сущности: это инфраструктурная таблица,
// а не доменная сущность, и ей нужен именно «набор строк» SQL — FOR UPDATE SKIP LOCKED с
// LIMIT и пакетный UPDATE по списку id. В JPA это пришлось бы обходить native-запросами и
// Persistable ради присвоенного id, не получив взамен ничего (маппинг, lazy, каскады не нужны).
//
// JdbcClient работает в той же транзакции, что и JPA: JpaTransactionManager отдаёт ему то же
// JDBC-соединение. Поэтому insert() внутри транзакции смены статуса платежа — одна атомарная
// запись.
@Repository
@RequiredArgsConstructor
public class OutboxRepository {

    private final JdbcClient jdbc;

    public void insert(UUID id, UUID aggregateId, String topic, String eventType,
                       String payloadJson, OffsetDateTime createdAt) {
        jdbc.sql("""
                        INSERT INTO outbox (id, aggregate_id, topic, event_type, payload, created_at)
                        VALUES (:id, :aggregateId, :topic, :eventType, CAST(:payload AS jsonb), :createdAt)
                        """)
                .param("id", id)
                .param("aggregateId", aggregateId)
                .param("topic", topic)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .param("createdAt", createdAt)
                .update();
    }

    // Неопубликованные строки по порядку создания, с блокировкой.
    //  - ORDER BY created_at, id: порядок отправки = порядок записи; id (UUIDv7, растёт со
    //    временем) разрешает совпадающие created_at детерминированно.
    //  - FOR UPDATE: строки заблокированы до конца транзакции relay.
    //  - SKIP LOCKED: другой экземпляр relay не ждёт эти строки, а берёт следующие.
    //    ВНИМАНИЕ: это не даёт строгого порядка при нескольких экземплярах — второй relay может
    //    отправить более поздние сообщения того же bookingId, пока первый держит более ранние.
    //    Пока экземпляр один, это не проявляется; см. PAYMENT_DESIGN.md §4.
    // payload::text — JSONB отдаётся как текст (Postgres хранит jsonb нормализованным: порядок
    // ключей и пробелы не сохраняются; для JSON-потребителя это не важно).
    public List<OutboxMessage> lockUnpublishedBatch(int limit) {
        return jdbc.sql("""
                        SELECT id, aggregate_id, topic, event_type, payload::text AS payload
                          FROM outbox
                         WHERE published_at IS NULL
                         ORDER BY created_at, id
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxMessage(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
    }

    public int markPublished(Collection<UUID> ids, OffsetDateTime publishedAt) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE outbox SET published_at = :publishedAt WHERE id IN (:ids)")
                .param("publishedAt", publishedAt)
                .param("ids", ids)
                .update();
    }
}
