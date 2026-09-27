package com.team1.ticket.ticket.service;

import com.team1.ticket.client.ExpoClient;
import com.team1.ticket.client.ExpoSummary;
import com.team1.ticket.client.RecommendationClient;
import com.team1.ticket.client.RoundClient;
import com.team1.ticket.client.RoundInfo;
import com.team1.ticket.common.ApiException;
import com.team1.ticket.common.ErrorCode;
import com.team1.ticket.ticket.entity.CheckinMethod;
import com.team1.ticket.ticket.entity.Ticket;
import com.team1.ticket.ticket.entity.TicketStatus;
import com.team1.ticket.ticket.repository.TicketRepository;
import com.team1.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// S7-3 체크인 가능 시간창. 회차 시작 1시간 전 ~ 회차 종료.
// 시계를 고정하고 회차 시각을 옮겨가며 경계를 본다.
class CheckinTimeWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-20T05:00:00Z");
    private static final Duration OPENS_BEFORE = Duration.ofHours(1);
    private static final long EXPO_ID = 10L;
    private static final long ROUND_ID = 45L;
    private static final long OWNER_ID = 7L;

    private static final AuthenticatedUser OWNER = new AuthenticatedUser(OWNER_ID, "ORGANIZER");

    private TicketRepository tickets;
    private ExpoClient expoClient;
    private RoundClient roundClient;
    private TicketCheckinService service;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketRepository.class);
        expoClient = mock(ExpoClient.class);
        roundClient = mock(RoundClient.class);
        service = new TicketCheckinService(tickets, expoClient, roundClient,
                mock(CheckinLogWriter.class), mock(RecommendationClient.class),
                Clock.fixed(NOW, ZoneOffset.UTC), OPENS_BEFORE);

        ticket = Ticket.issue(123L, "R-4K7Q-W2M8", EXPO_ID, ROUND_ID, 77L, 2, "tok-1", NOW.minusSeconds(86400));
        when(tickets.findByIdForUpdate(anyLong())).thenReturn(Optional.of(ticket));
        when(expoClient.getExpo(EXPO_ID)).thenReturn(new ExpoSummary(EXPO_ID, OWNER_ID, "PUBLISHED", "테크 잡페어"));
    }

    // 회차가 NOW 기준 startsIn 뒤에 시작해 두 시간 동안 열린다.
    private void givenRoundStartingIn(Duration startsIn) {
        Instant startsAt = NOW.plus(startsIn);
        when(roundClient.findRound(ROUND_ID))
                .thenReturn(new RoundInfo(ROUND_ID, 1, startsAt, startsAt.plus(Duration.ofHours(2))));
    }

    @Test
    @DisplayName("창 시작 1분 전이면 거절하고, 언제부터 가능한지를 메시지에 담는다")
    void rejectsOneMinuteBeforeWindowOpens() {
        givenRoundStartingIn(Duration.ofHours(1).plusMinutes(1));

        assertThatThrownBy(() -> service.checkin(1L, CheckinMethod.QR, OWNER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CHECKIN_NOT_OPEN);
                    assertThat(e.getMessage()).contains(NOW.plusSeconds(60).toString());
                });
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED);
    }

    @Test
    @DisplayName("창 시작 정각이면 허용한다 (경계 포함)")
    void allowsExactlyWhenWindowOpens() {
        givenRoundStartingIn(OPENS_BEFORE);

        service.checkin(1L, CheckinMethod.QR, OWNER);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    @DisplayName("회차 진행 중이면 허용한다")
    void allowsDuringRound() {
        givenRoundStartingIn(Duration.ofHours(-1));

        service.checkin(1L, CheckinMethod.QR, OWNER);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    @DisplayName("회차 종료 후면 거절한다")
    void rejectsAfterRoundEnds() {
        givenRoundStartingIn(Duration.ofHours(-3)); // 2시간짜리 회차가 1시간 전에 끝났다

        assertThatThrownBy(() -> service.checkin(1L, CheckinMethod.QR, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CHECKIN_CLOSED));
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED);
    }

    @Test
    @DisplayName("회차 조회가 실패하면 체크인을 허용한다 (fail-open) - 입장 줄을 멈추지 않는다")
    void allowsWhenRoundLookupFails() {
        when(roundClient.findRound(ROUND_ID)).thenReturn(null);

        service.checkin(1L, CheckinMethod.QR, OWNER);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    @DisplayName("소유권 조회 실패는 여전히 거절한다 (fail-closed) - 시간창과 정책이 다르다")
    void stillFailsClosedWhenExpoLookupFails() {
        givenRoundStartingIn(Duration.ofHours(-1));
        when(expoClient.getExpo(EXPO_ID))
                .thenThrow(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "expo-service unavailable"));

        assertThatThrownBy(() -> service.checkin(1L, CheckinMethod.QR, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED);
    }
}
