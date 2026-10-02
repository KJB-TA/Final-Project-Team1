package com.team1.ticket.ticket.service;

import com.team1.ticket.client.ExpoClient;
import com.team1.ticket.client.ExpoSummary;
import com.team1.ticket.client.RecommendationClient;
import com.team1.ticket.client.RoundClient;
import com.team1.ticket.common.ApiException;
import com.team1.ticket.common.ErrorCode;
import com.team1.ticket.ticket.dto.CheckinResult;
import com.team1.ticket.ticket.entity.CheckinAction;
import com.team1.ticket.ticket.entity.CheckinMethod;
import com.team1.ticket.ticket.entity.Ticket;
import com.team1.ticket.ticket.entity.TicketStatus;
import com.team1.ticket.ticket.repository.TicketRepository;
import com.team1.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// S7-4 체크인 되돌리기. 수동 입력(S7-2)이 생겨 오입력 가능성이 커진 만큼 복구 경로가 필요하다.
class CheckinCancellationTest {

    private static final Instant NOW = Instant.parse("2026-09-20T05:00:00Z");
    private static final long EXPO_ID = 10L;
    private static final long OWNER_ID = 7L;

    private static final AuthenticatedUser OWNER = new AuthenticatedUser(OWNER_ID, "ORGANIZER");
    private static final AuthenticatedUser OTHER_ORGANIZER = new AuthenticatedUser(99L, "ORGANIZER");

    private TicketRepository tickets;
    private ExpoClient expoClient;
    private RoundClient roundClient;
    private CheckinLogWriter checkinLogWriter;
    private TicketCheckinService service;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketRepository.class);
        expoClient = mock(ExpoClient.class);
        roundClient = mock(RoundClient.class);   // null -> 시간창은 fail-open
        checkinLogWriter = mock(CheckinLogWriter.class);
        service = new TicketCheckinService(tickets, expoClient, roundClient,
                checkinLogWriter, mock(RecommendationClient.class),
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(1));

        ticket = Ticket.issue(123L, "R-4K7Q-W2M8", EXPO_ID, 45L, 77L, 2, "tok-1", NOW.minusSeconds(3600));
        when(tickets.findByIdForUpdate(anyLong())).thenReturn(Optional.of(ticket));
        when(expoClient.getExpo(EXPO_ID)).thenReturn(new ExpoSummary(EXPO_ID, OWNER_ID, "PUBLISHED", "테크 잡페어"));
    }

    @Test
    @DisplayName("USED 를 ISSUED 로 되돌리고 사용 시각을 비운다")
    void undoesCheckin() {
        ticket.checkIn(NOW.minusSeconds(60));

        CheckinResult result = service.cancelCheckin(1L, OWNER);

        assertThat(result.status()).isEqualTo("ISSUED");
        assertThat(result.checkedInAt()).isNull();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED);
        assertThat(ticket.getUsedAt()).isNull();
    }

    @Test
    @DisplayName("되돌린 뒤 다시 체크인할 수 있다")
    void allowsCheckinAgainAfterUndo() {
        ticket.checkIn(NOW.minusSeconds(60));
        service.cancelCheckin(1L, OWNER);

        service.checkin(1L, CheckinMethod.QR, OWNER);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
        assertThat(ticket.getUsedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("되돌리면 CANCEL 이력이 1건 남는다")
    void recordsCancelOnce() {
        ticket.checkIn(NOW.minusSeconds(60));

        service.cancelCheckin(1L, OWNER);

        verify(checkinLogWriter).record(any(), eq(CheckinAction.CANCEL), eq(OWNER_ID), isNull(), eq(NOW));
    }

    @Test
    @DisplayName("이미 ISSUED 면 멱등으로 통과하고, 이력은 남기지 않는다")
    void isIdempotentForIssuedTicket() {
        CheckinResult result = service.cancelCheckin(1L, OWNER);

        assertThat(result.status()).isEqualTo("ISSUED");
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED);
        // 두 번 눌렀다고 이력이 두 줄이면 이력이 거짓말을 한다.
        verify(checkinLogWriter, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("CANCELLED 티켓은 되돌릴 대상이 아니다 (409)")
    void rejectsCancelledTicket() {
        ticket.cancel();

        assertThatThrownBy(() -> service.cancelCheckin(1L, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    @DisplayName("남의 박람회 티켓은 403 이고 전이하지 않는다")
    void rejectsNonOwner() {
        ticket.checkIn(NOW.minusSeconds(60));

        assertThatThrownBy(() -> service.cancelCheckin(1L, OTHER_ORGANIZER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
    }
}
