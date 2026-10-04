package com.cineflow.booking;

import com.cineflow.booking.exception.SeatHoldConflictException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Redis-удержания: SET NX не пускает второго; снятие чужого удержания не работает.
class RedisHoldTest extends AbstractBookingIntegrationTest {

    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    @Test
    void nxBlocksSecondUser_andReleaseIsOwnerChecked() {
        redisHoldService.hold(SCREENING, List.of(100L), userA);
        assertThat(redisHoldService.currentOwner(SCREENING, 100L)).isEqualTo(userA.toString());

        // Второй пользователь не может удержать занятое место (NX не сработал).
        assertThatThrownBy(() -> redisHoldService.hold(SCREENING, List.of(100L), userB))
                .isInstanceOf(SeatHoldConflictException.class);

        // Чужое удержание снять нельзя — владелец остаётся прежним.
        redisHoldService.release(SCREENING, List.of(100L), userB);
        assertThat(redisHoldService.currentOwner(SCREENING, 100L)).isEqualTo(userA.toString());

        // Владелец снимает — место свободно, теперь его может взять второй.
        redisHoldService.release(SCREENING, List.of(100L), userA);
        assertThat(redisHoldService.currentOwner(SCREENING, 100L)).isNull();

        redisHoldService.hold(SCREENING, List.of(100L), userB);
        assertThat(redisHoldService.currentOwner(SCREENING, 100L)).isEqualTo(userB.toString());
    }

    @Test
    void partialConflict_rollsBackAlreadyAcquiredHolds() {
        redisHoldService.hold(SCREENING, List.of(200L), userA);

        // userB просит [100, 200]: 100 свободно (захватит), 200 занято userA → конфликт.
        // Захваченное в этой попытке (100) должно откатиться, а не остаться висеть за userB.
        assertThatThrownBy(() -> redisHoldService.hold(SCREENING, List.of(100L, 200L), userB))
                .isInstanceOf(SeatHoldConflictException.class);

        assertThat(redisHoldService.currentOwner(SCREENING, 100L)).isNull();
        assertThat(redisHoldService.currentOwner(SCREENING, 200L)).isEqualTo(userA.toString());
    }
}
