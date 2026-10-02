package com.team1.ticket.ticket.service;

import com.team1.ticket.client.ExpoClient;
import com.team1.ticket.client.ExpoSummary;
import com.team1.ticket.client.RecommendationClient;
import com.team1.ticket.client.RoundClient;
import com.team1.ticket.common.ApiException;
import com.team1.ticket.common.ErrorCode;
import com.team1.ticket.ticket.dto.CheckinResult;
import com.team1.ticket.ticket.dto.CheckinTicketView;
import com.team1.ticket.ticket.entity.Ticket;
import com.team1.ticket.ticket.entity.TicketStatus;
import com.team1.ticket.ticket.entity.CheckinMethod;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketCheckinServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T05:00:00Z");
    private static final long EXPO_ID = 10L;
    private static final long OWNER_ID = 7L;
    private static final String TOKEN = "tok-1";
    private static final String RESERVATION_NO = "R-4K7Q-W2M8";

    private static final AuthenticatedUser OWNER = new AuthenticatedUser(OWNER_ID, "ORGANIZER");
    private static final AuthenticatedUser OTHER_ORGANIZER = new AuthenticatedUser(99L, "ORGANIZER");
    private static final AuthenticatedUser MEMBER = new AuthenticatedUser(OWNER_ID, "USER");

    private TicketRepository tickets;
    private ExpoClient expoClient;
    private RoundClient roundClient;
    private CheckinLogWriter checkinLogWriter;
    private TicketCheckinService service;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketRepository.class);
        expoClient = mock(ExpoClient.class);
        // findRound 기본값 null -> 시간창 검증은 fail-open 으로 건너뛴다. 경계는 CheckinTimeWindowTest 가 본다.
        roundClient = mock(RoundClient.class);
        checkinLogWriter = mock(CheckinLogWriter.class);
        service = new TicketCheckinService(tickets, expoClient, roundClient,
                checkinLogWriter, mock(RecommendationClient.class),
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(1));
    }

    private Ticket issuedTicket() {
        return Ticket.issue(123L, RESERVATION_NO, EXPO_ID, 45L, 77L, 2, TOKEN, NOW.minusSeconds(3600));
    }

    private void ownedExpo() {
        when(expoClient.getExpo(EXPO_ID)).thenReturn(new ExpoSummary(EXPO_ID, OWNER_ID, "PUBLISHED", "테크 잡페어"));
    }

    // ---- verify ----

    @Test
    @DisplayName("verify: 소유 주최자가 토큰으로 조회하면 티켓 정보를 반환하고 상태는 그대로다(미전이)")
    void verifyReturnsInfoWithoutTransition() {
        Ticket ticket = issuedTicket();
        when(tickets.findByCheckinToken(TOKEN)).thenReturn(Optional.of(ticket));
        ownedExpo();

        CheckinTicketView view = service.verify(TOKEN, null, OWNER);

        assertThat(view.status()).isEqualTo("ISSUED");
        assertThat(view.headcount()).isEqualTo(2);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED); // 미전이
    }

    @Test
    @DisplayName("verify: 존재하지 않는 토큰이면 404")
    void verifyRejectsUnknownToken() {
        when(tickets.findByCheckinToken(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(TOKEN, null, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("verify: 주최자 역할이 아니면 403 이고 박람회 조회조차 하지 않는다")
    void verifyRejectsNonOrganizer() {
        when(tickets.findByCheckinToken(TOKEN)).thenReturn(Optional.of(issuedTicket()));

        assertThatThrownBy(() -> service.verify(TOKEN, null, MEMBER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(expoClient, never()).getExpo(anyLong());
    }

    @Test
    @DisplayName("verify: 회원이 없는 코드·예약번호를 넣어도 404 가 아니라 403 - 티켓을 찾기 전에 역할부터 본다")
    void verifyChecksRoleBeforeLookup() {
        when(tickets.findByCheckinToken(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(TOKEN, null, MEMBER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.verify(null, RESERVATION_NO, MEMBER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(tickets, never()).findByCheckinToken(any());
        verify(tickets, never()).findByReservationNo(any());
    }

    @Test
    @DisplayName("checkin: 회원이 없는 티켓 id 로 요청해도 404 가 아니라 403")
    void checkinChecksRoleBeforeLookup() {
        assertThatThrownBy(() -> service.checkin(999L, null, MEMBER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(tickets, never()).findById(anyLong());
    }

    @Test
    @DisplayName("verify: 다른 주최자면 403")
    void verifyRejectsNonOwner() {
        when(tickets.findByCheckinToken(TOKEN)).thenReturn(Optional.of(issuedTicket()));
        ownedExpo();

        assertThatThrownBy(() -> service.verify(TOKEN, null, OTHER_ORGANIZER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("verify: 예약번호로도 같은 티켓을 조회한다 (QR 을 못 쓰는 경우)")
    void verifyFindsByReservationNo() {
        Ticket ticket = issuedTicket();
        when(tickets.findByReservationNo(RESERVATION_NO)).thenReturn(Optional.of(ticket));
        ownedExpo();

        CheckinTicketView view = service.verify(null, RESERVATION_NO, OWNER);

        assertThat(view.reservationNo()).isEqualTo(RESERVATION_NO);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED); // 미전이
        verify(tickets, never()).findByCheckinToken(any());
    }

    @Test
    @DisplayName("verify: 예약번호는 소문자·공백으로 입력해도 조회된다 (창구에서 받아 적는 값이다)")
    void verifyNormalizesReservationNo() {
        when(tickets.findByReservationNo(RESERVATION_NO)).thenReturn(Optional.of(issuedTicket()));
        ownedExpo();

        assertThat(service.verify(null, "  r-4k7q-w2m8  ", OWNER)).isNotNull();
    }

    @Test
    @DisplayName("verify: 없는 예약번호는 토큰과 같은 404 메시지를 쓴다 (예약번호 존재 여부를 흘리지 않는다)")
    void verifyHidesWhetherReservationNoExists() {
        when(tickets.findByReservationNo(RESERVATION_NO)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(null, RESERVATION_NO, OWNER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("invalid ticket");
                });
    }

    @Test
    @DisplayName("verify: 토큰과 예약번호를 둘 다 주면 400")
    void verifyRejectsBothParameters() {
        assertThatThrownBy(() -> service.verify(TOKEN, RESERVATION_NO, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("verify: 둘 다 비어 있으면 400")
    void verifyRejectsNeitherParameter() {
        assertThatThrownBy(() -> service.verify("  ", null, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    // ---- checkin ----

    @Test
    @DisplayName("checkin: 소유 주최자가 확정하면 USED 로 전이하고 사용 시각을 기록한다")
    void checkinTransitionsToUsed() {
        Ticket ticket = issuedTicket();
        when(tickets.findByIdForUpdate(anyLong())).thenReturn(Optional.of(ticket));
        ownedExpo();

        CheckinResult result = service.checkin(1L, CheckinMethod.QR, OWNER);

        assertThat(result.status()).isEqualTo("USED");
        assertThat(result.checkedInAt()).isEqualTo(NOW);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    @DisplayName("checkin: 없는 티켓이면 404")
    void checkinRejectsUnknownTicket() {
        when(tickets.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkin(1L, CheckinMethod.QR, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("checkin: 이미 체크인된 티켓이면 409")
    void checkinRejectsAlreadyUsed() {
        Ticket ticket = issuedTicket();
        ticket.checkIn(NOW.minusSeconds(60));
        when(tickets.findByIdForUpdate(anyLong())).thenReturn(Optional.of(ticket));
        ownedExpo();

        assertThatThrownBy(() -> service.checkin(1L, CheckinMethod.QR, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("checkin: 다른 주최자면 403 이고 전이하지 않는다")
    void checkinRejectsNonOwner() {
        Ticket ticket = issuedTicket();
        when(tickets.findByIdForUpdate(anyLong())).thenReturn(Optional.of(ticket));
        ownedExpo();

        assertThatThrownBy(() -> service.checkin(1L, CheckinMethod.QR, OTHER_ORGANIZER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED);
    }

    @Test
    @DisplayName("checkin: 이력 기록이 실패해도 체크인은 성사된다 (이력은 부가 기능)")
    void checkinSurvivesLoggingFailure() {
        Ticket ticket = issuedTicket();
        when(tickets.findByIdForUpdate(anyLong())).thenReturn(Optional.of(ticket));
        ownedExpo();
        doThrow(new RuntimeException("log db down"))
                .when(checkinLogWriter).record(any(), any(), any(), any(), any());

        CheckinResult result = service.checkin(1L, CheckinMethod.QR, OWNER);

        assertThat(result.status()).isEqualTo("USED");
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    @DisplayName("checkin: 박람회 조회 실패면 503(fail-closed)이고 전이하지 않는다")
    void checkinFailsClosedWhenExpoUnavailable() {
        Ticket ticket = issuedTicket();
        when(tickets.findByIdForUpdate(anyLong())).thenReturn(Optional.of(ticket));
        when(expoClient.getExpo(EXPO_ID))
                .thenThrow(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "expo-service unavailable"));

        assertThatThrownBy(() -> service.checkin(1L, CheckinMethod.QR, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED);
    }
}
