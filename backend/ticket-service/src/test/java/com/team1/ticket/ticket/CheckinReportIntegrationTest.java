package com.team1.ticket.ticket;

import com.team1.ai.GeminiClient;
import com.team1.security.AuthenticatedUser;
import com.team1.ticket.client.ExpoSummary;
import com.team1.ticket.client.ReservationSummary;
import com.team1.ticket.client.ReservationSummaryClient;
import com.team1.ticket.common.ApiException;
import com.team1.ticket.support.IntegrationTestSupport;
import com.team1.ticket.ticket.dto.CheckinReportResponse;
import com.team1.ticket.ticket.entity.CheckinAction;
import com.team1.ticket.ticket.entity.CheckinLog;
import com.team1.ticket.ticket.entity.CheckinMethod;
import com.team1.ticket.ticket.entity.Ticket;
import com.team1.ticket.ticket.repository.CheckinLogRepository;
import com.team1.ticket.ticket.repository.TicketRepository;
import com.team1.ticket.ticket.service.CheckinReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * #259 체크인 결과 요약. 이력 집계 JPQL 과 폴백 동작을 실제 MySQL 로 검증한다.
 */
class CheckinReportIntegrationTest extends IntegrationTestSupport {

    private static final long EXPO_ID = 10L;
    private static final long OWNER_ID = 77L;
    private static final AuthenticatedUser OWNER = new AuthenticatedUser(OWNER_ID, "ORGANIZER");

    // 한국 시간 기준 14시·15시가 되도록 UTC 로 05:00, 06:00 을 쓴다.
    private static final Instant AT_14 = Instant.parse("2026-09-20T05:00:00Z");
    private static final Instant AT_15 = Instant.parse("2026-09-20T06:30:00Z");
    // 다음 날 같은 14시(한국 시간). 날짜 없이 시간만 모으면 AT_14 와 합쳐진다.
    private static final Instant NEXT_DAY_14 = Instant.parse("2026-09-21T05:10:00Z");
    private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 20);
    private static final LocalDate DAY_2 = LocalDate.of(2026, 9, 21);

    private static final long ROUND_1 = 45L;
    private static final long ROUND_2 = 46L;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private CheckinLogRepository checkinLogRepository;

    @Autowired
    private CheckinReportService reportService;

    @MockBean
    private ReservationSummaryClient reservationSummaryClient;

    @MockBean
    private GeminiClient geminiClient;

    @BeforeEach
    void setUp() {
        checkinLogRepository.deleteAll();
        ticketRepository.deleteAll();

        when(expoClient.getExpo(anyLong()))
                .thenReturn(new ExpoSummary(EXPO_ID, OWNER_ID, "CLOSED", "2026 로봇 박람회"));
        when(reservationSummaryClient.findSummaries(EXPO_ID)).thenReturn(List.of(
                new ReservationSummary(ROUND_1, 100, 30, 2, AT_14, AT_15)));
        when(geminiClient.isAvailable()).thenReturn(true);
        when(geminiClient.generateJson(anyString(), anyString(), any()))
                .thenReturn(new CheckinReportService.Summary("입장률이 높았습니다."));
    }

    private Ticket checkedIn(long reservationId, int headcount, String token, Instant at,
                             CheckinMethod method) {
        return checkedIn(reservationId, ROUND_1, headcount, token, at, method);
    }

    private Ticket checkedIn(long reservationId, long roundId, int headcount, String token, Instant at,
                             CheckinMethod method) {
        Ticket ticket = Ticket.issue(reservationId, "R-" + reservationId, EXPO_ID, roundId,
                OWNER_ID, headcount, token, at);
        ticket.checkIn(at);
        Ticket saved = ticketRepository.save(ticket);
        checkinLogRepository.save(
                CheckinLog.of(saved.getId(), CheckinAction.CHECK_IN, OWNER_ID, method, at));
        return saved;
    }

    @Test
    @DisplayName("예약 대비 입장·노쇼·입장률을 낸다")
    void aggregatesAttendance() {
        checkedIn(1L, 10, "t1", AT_14, CheckinMethod.QR);
        checkedIn(2L, 5, "t2", AT_15, CheckinMethod.RESERVATION_NO);

        CheckinReportResponse report = reportService.getReport(EXPO_ID, OWNER);

        assertThat(report.reserved()).isEqualTo(30);
        assertThat(report.checkedIn()).isEqualTo(15);
        assertThat(report.noShow()).isEqualTo(15);
        assertThat(report.checkinRate()).isEqualTo(50);
        assertThat(report.expoTitle()).isEqualTo("2026 로봇 박람회");
    }

    @Test
    @DisplayName("입장 시각을 한국 시간 기준 시간대로 모은다")
    void groupsByKoreanHour() {
        checkedIn(1L, 1, "t1", AT_14, CheckinMethod.QR);
        checkedIn(2L, 1, "t2", AT_14, CheckinMethod.QR);
        checkedIn(3L, 1, "t3", AT_15, CheckinMethod.QR);

        CheckinReportResponse report = reportService.getReport(EXPO_ID, OWNER);

        assertThat(report.hourly())
                .containsExactly(new CheckinReportResponse.HourlyCheckin(DAY_1, 14, 2),
                        new CheckinReportResponse.HourlyCheckin(DAY_1, 15, 1));
    }

    @Test
    @DisplayName("QR 과 예약번호 처리 건수를 나눠 센다 - 방법을 안 보냈으면 UNKNOWN")
    void countsByMethod() {
        checkedIn(1L, 1, "t1", AT_14, CheckinMethod.QR);
        checkedIn(2L, 1, "t2", AT_14, CheckinMethod.QR);
        checkedIn(3L, 1, "t3", AT_14, CheckinMethod.RESERVATION_NO);
        checkedIn(4L, 1, "t4", AT_14, null);

        CheckinReportResponse report = reportService.getReport(EXPO_ID, OWNER);

        assertThat(report.byMethod())
                .containsEntry("QR", 2L)
                .containsEntry("RESERVATION_NO", 1L)
                .containsEntry("UNKNOWN", 1L);
    }

    @Test
    @DisplayName("되돌린 체크인 건수를 따로 센다 - 입장 집계에는 섞이지 않는다")
    void countsRevertedSeparately() {
        Ticket ticket = checkedIn(1L, 3, "t1", AT_14, CheckinMethod.QR);
        checkinLogRepository.save(
                CheckinLog.of(ticket.getId(), CheckinAction.CANCEL, OWNER_ID, CheckinMethod.QR, AT_15));

        CheckinReportResponse report = reportService.getReport(EXPO_ID, OWNER);

        assertThat(report.reverted()).isEqualTo(1);
        assertThat(report.hourly()).containsExactly(new CheckinReportResponse.HourlyCheckin(DAY_1, 14, 1));
    }

    @Test
    @DisplayName("프롬프트에는 처리 방법을 한글로 넘긴다 - 요약 문장에 enum 이름이 새지 않게")
    void passesKoreanMethodLabelToPrompt() {
        checkedIn(1L, 1, "t1", AT_14, CheckinMethod.RESERVATION_NO);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        reportService.getReport(EXPO_ID, OWNER);

        org.mockito.Mockito.verify(geminiClient).generateJson(anyString(), prompt.capture(), any());
        assertThat(prompt.getValue())
                .contains("예약번호 1건")
                .doesNotContain("RESERVATION_NO");
    }

    @Test
    @DisplayName("LLM 이 죽어도 숫자는 그대로 내려간다 - 요약만 빈다")
    void keepsNumbersWhenLlmFails() {
        checkedIn(1L, 10, "t1", AT_14, CheckinMethod.QR);
        when(geminiClient.generateJson(anyString(), anyString(), any())).thenReturn(null);

        CheckinReportResponse report = reportService.getReport(EXPO_ID, OWNER);

        assertThat(report.summary()).isNull();
        assertThat(report.checkedIn()).isEqualTo(10);
    }

    @Test
    @DisplayName("입장 기록이 없으면 LLM 을 부르지 않는다")
    void skipsLlmWhenNoCheckin() {
        CheckinReportResponse report = reportService.getReport(EXPO_ID, OWNER);

        assertThat(report.checkedIn()).isZero();
        assertThat(report.summary()).isNull();
        assertThat(report.hourly()).isEmpty();
    }

    @Test
    @DisplayName("예약 현황 조회가 실패해도 입장 집계는 낸다")
    void toleratesReservationServiceFailure() {
        checkedIn(1L, 4, "t1", AT_14, CheckinMethod.QR);
        when(reservationSummaryClient.findSummaries(EXPO_ID)).thenReturn(List.of());

        CheckinReportResponse report = reportService.getReport(EXPO_ID, OWNER);

        assertThat(report.reserved()).isZero();
        assertThat(report.checkedIn()).isEqualTo(4);
        assertThat(report.noShow()).isZero();
        assertThat(report.checkinRate()).isZero();
    }

    @Test
    @DisplayName("회차별 예약 대비 입장을 시작 순서대로 번호를 붙여 낸다 - 전체는 합산")
    void breaksDownByRound() {
        twoRounds();
        checkedIn(1L, ROUND_1, 16, "t1", AT_14, CheckinMethod.QR);
        checkedIn(2L, ROUND_2, 3, "t2", NEXT_DAY_14, CheckinMethod.QR);

        CheckinReportResponse report = reportService.getReport(EXPO_ID, OWNER);

        assertThat(report.reserved()).isEqualTo(30);
        assertThat(report.checkedIn()).isEqualTo(19);
        assertThat(report.rounds()).containsExactly(
                new CheckinReportResponse.RoundCheckin(ROUND_1, 1, AT_14, 20, 16, 4, 80),
                new CheckinReportResponse.RoundCheckin(ROUND_2, 2, NEXT_DAY_14, 10, 3, 7, 30));
    }

    @Test
    @DisplayName("다른 날의 같은 시간대는 합치지 않는다")
    void keepsSameHourOnDifferentDaysApart() {
        checkedIn(1L, 1, "t1", AT_14, CheckinMethod.QR);
        checkedIn(2L, 1, "t2", NEXT_DAY_14, CheckinMethod.QR);

        CheckinReportResponse report = reportService.getReport(EXPO_ID, OWNER);

        assertThat(report.hourly()).containsExactly(
                new CheckinReportResponse.HourlyCheckin(DAY_1, 14, 1),
                new CheckinReportResponse.HourlyCheckin(DAY_2, 14, 1));
    }

    @Test
    @DisplayName("프롬프트에 전체 합산임을 밝히고 회차별 수치를 넘긴다")
    void passesRoundBreakdownToPrompt() {
        twoRounds();
        checkedIn(1L, ROUND_1, 16, "t1", AT_14, CheckinMethod.QR);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        reportService.getReport(EXPO_ID, OWNER);

        org.mockito.Mockito.verify(geminiClient).generateJson(anyString(), prompt.capture(), any());
        assertThat(prompt.getValue())
                .contains("모든 회차 합산")
                .contains("1회차(9/20 14시): 예약 20명, 입장 16명, 입장률 80%")
                .contains("2회차(9/21 14시): 예약 10명, 입장 0명, 입장률 0%")
                .contains("9/20 14시 1명");
    }

    @Test
    @DisplayName("예약 현황을 못 받아오면 입장 기록이 있는 회차만 번호 없이 낸다")
    void listsCheckedInRoundsWithoutSequenceWhenReservationsMissing() {
        when(reservationSummaryClient.findSummaries(EXPO_ID)).thenReturn(List.of());
        checkedIn(1L, ROUND_2, 2, "t1", AT_14, CheckinMethod.QR);

        CheckinReportResponse report = reportService.getReport(EXPO_ID, OWNER);

        assertThat(report.rounds()).containsExactly(
                new CheckinReportResponse.RoundCheckin(ROUND_2, null, null, 0, 2, 0, 0));
    }

    private void twoRounds() {
        when(reservationSummaryClient.findSummaries(EXPO_ID)).thenReturn(List.of(
                new ReservationSummary(ROUND_1, 100, 20, 0, AT_14, AT_15),
                new ReservationSummary(ROUND_2, 100, 10, 0, NEXT_DAY_14, NEXT_DAY_14.plusSeconds(3600))));
    }

    @Test
    @DisplayName("남의 박람회는 볼 수 없다")
    void rejectsNonOwner() {
        assertThatThrownBy(() -> reportService.getReport(EXPO_ID, new AuthenticatedUser(999L, "ORGANIZER")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not the owner");
    }

    @Test
    @DisplayName("주최자가 아니면 볼 수 없다")
    void rejectsNonOrganizer() {
        assertThatThrownBy(() -> reportService.getReport(EXPO_ID, new AuthenticatedUser(OWNER_ID, "USER")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("organizer role required");
    }
}
