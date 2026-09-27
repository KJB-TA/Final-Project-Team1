package com.team1.reservation.round;

import com.team1.reservation.client.ExpoClient;
import com.team1.reservation.client.ExpoSummary;
import com.team1.reservation.common.ApiException;
import com.team1.reservation.common.ErrorCode;
import com.team1.reservation.round.repository.RoundRepository;
import com.team1.reservation.round.dto.CreateRoundRequest;
import com.team1.reservation.round.entity.Round;
import com.team1.reservation.round.service.RoundService;
import com.team1.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoundServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final Instant STARTS = Instant.parse("2026-09-10T02:00:00Z");
    private static final Instant ENDS = Instant.parse("2026-09-10T08:00:00Z");

    private static final AuthenticatedUser OWNER = new AuthenticatedUser(7L, "ORGANIZER");
    private static final AuthenticatedUser OTHER_ORGANIZER = new AuthenticatedUser(99L, "ORGANIZER");
    private static final AuthenticatedUser MEMBER = new AuthenticatedUser(7L, "USER");

    private RoundRepository rounds;
    private ExpoClient expoClient;
    private RoundService service;

    @BeforeEach
    void setUp() {
        rounds = mock(RoundRepository.class);
        expoClient = mock(ExpoClient.class);
        service = new RoundService(rounds, expoClient, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CreateRoundRequest request(int capacity) {
        return new CreateRoundRequest(STARTS, ENDS, capacity, 10000);
    }

    @Test
    @DisplayName("소유 주최자의 회차 등록은 저장된다")
    void createsRoundForOwner() {
        when(expoClient.getExpo(1L)).thenReturn(new ExpoSummary(1L, OWNER.userId(), "HIDDEN"));
        when(rounds.save(any(Round.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Round saved = service.create(1L, OWNER, request(100));

        assertThat(saved.getExpoId()).isEqualTo(1L);
        assertThat(saved.getCapacity()).isEqualTo(100);
        assertThat(saved.getFee()).isEqualTo(10000);
        verify(rounds).save(any(Round.class));
    }

    @Test
    @DisplayName("다른 주최자의 박람회면 403 이고 회차는 저장되지 않는다")
    void rejectsOtherOrganizer() {
        when(expoClient.getExpo(1L)).thenReturn(new ExpoSummary(1L, OWNER.userId(), "HIDDEN"));

        assertThatThrownBy(() -> service.create(1L, OTHER_ORGANIZER, request(100)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(rounds, never()).save(any(Round.class));
    }

    @Test
    @DisplayName("USER 역할이면 403 이고 박람회 조회조차 하지 않는다")
    void rejectsMember() {
        assertThatThrownBy(() -> service.create(1L, MEMBER, request(100)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(expoClient, never()).getExpo(any());
        verify(rounds, never()).save(any(Round.class));
    }

    @Test
    @DisplayName("정원이 1 미만이면 400 이고 내부 호출을 하지 않는다")
    void rejectsCapacityBelowOne() {
        assertThatThrownBy(() -> service.create(1L, OWNER, request(0)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verify(expoClient, never()).getExpo(any());
        verify(rounds, never()).save(any(Round.class));
    }

    @Test
    @DisplayName("박람회 조회가 실패하면 503 이고 회차는 저장되지 않는다 (fail-closed)")
    void failsClosedWhenExpoServiceUnavailable() {
        when(expoClient.getExpo(1L))
                .thenThrow(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "expo-service unavailable"));

        assertThatThrownBy(() -> service.create(1L, OWNER, request(100)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));

        verify(rounds, never()).save(any(Round.class));
    }

    @Test
    @DisplayName("HIDDEN 상태 박람회의 회차도 소유 주최자에게는 반환한다")
    void listsRoundsOfHiddenExpoForOwner() {
        when(expoClient.getExpo(1L)).thenReturn(new ExpoSummary(1L, OWNER.userId(), "HIDDEN"));
        when(rounds.findByExpoIdAndDeletedAtIsNullOrderByStartsAtAsc(1L))
                .thenReturn(java.util.List.of(Round.create(1L, STARTS, ENDS, 50, 0, NOW)));

        assertThat(service.listForOrganizer(1L, OWNER)).hasSize(1);
    }

    @Test
    @DisplayName("자동 마감 대상 조회는 limit 을 상한으로 잘라서 요청한다")
    void capsFinishedExpoIdsLimit() {
        when(rounds.findExpoIdsWithAllRoundsEndedBefore(any(), anyLong(), any(Pageable.class)))
                .thenReturn(java.util.List.of(1L, 2L));

        assertThat(service.finishedExpoIds(NOW, 999_999)).containsExactly(1L, 2L);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(rounds).findExpoIdsWithAllRoundsEndedBefore(any(), anyLong(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(RoundService.MAX_FINISHED_EXPO_LIMIT);
    }

    @Test
    @DisplayName("limit 이 0 이하여도 최소 1 로 보정한다")
    void clampsNonPositiveLimit() {
        when(rounds.findExpoIdsWithAllRoundsEndedBefore(any(), anyLong(), any(Pageable.class)))
                .thenReturn(java.util.List.of());

        service.finishedExpoIds(NOW, 0);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(rounds).findExpoIdsWithAllRoundsEndedBefore(any(), anyLong(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }
}
