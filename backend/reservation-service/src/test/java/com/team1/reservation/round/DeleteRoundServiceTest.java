package com.team1.reservation.round;

import com.team1.payment.PaymentApprovalResult;
import com.team1.payment.PaymentService;
import com.team1.payment.PaymentTransaction;
import com.team1.reservation.client.ExpoClient;
import com.team1.reservation.client.ExpoSummary;
import com.team1.reservation.client.TicketClient;
import com.team1.reservation.common.ApiException;
import com.team1.reservation.common.ErrorCode;
import com.team1.reservation.reservation.dto.CreateReservationRequest;
import com.team1.reservation.reservation.entity.Reservation;
import com.team1.reservation.reservation.repository.ReservationRepository;
import com.team1.reservation.reservation.service.ReservationCancelService;
import com.team1.reservation.reservation.service.ReservationPaymentService;
import com.team1.reservation.reservation.service.ReservationService;
import com.team1.reservation.round.entity.Round;
import com.team1.reservation.round.repository.RoundRepository;
import com.team1.reservation.round.service.RoundService;
import com.team1.reservation.support.IntegrationTestSupport;
import com.team1.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S9-3 회차 소프트 삭제. 조건부 UPDATE 와 자동 비공개 순서를 실 DB 로 본다.
 *
 * <p>핵심은 자동 비공개 묶음이다 - 로컬 삭제가 먼저라 예약이 있으면 박람회를 건드리지 않고,
 * 비공개 전환이 실패하면 삭제가 롤백된다.
 */
class DeleteRoundServiceTest extends IntegrationTestSupport {

    private static final int CAPACITY = 10;
    private static final int FEE = 10000;
    private static final Long EXPO_ID = 1L;
    private static final long OWNER_ID = 99L;

    private static final AuthenticatedUser OWNER = new AuthenticatedUser(OWNER_ID, "ORGANIZER");
    private static final AuthenticatedUser OTHER_ORGANIZER = new AuthenticatedUser(7L, "ORGANIZER");

    @Autowired
    private RoundService roundService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationPaymentService paymentTransition;

    @Autowired
    private ReservationCancelService cancelService;

    @Autowired
    private RoundRepository rounds;

    @Autowired
    private ReservationRepository reservations;

    @MockitoBean
    private ExpoClient expoClient;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private TicketClient ticketClient;

    private Long roundId;
    private Instant now;

    @BeforeEach
    void setUp() {
        reservations.deleteAll();
        rounds.deleteAll();

        // MySQL DATETIME(6) 은 마이크로초까지다. 나노초를 그대로 쓰면 저장 시 반올림된다.
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        roundId = futureRound();

        givenExpoStatus("PUBLISHED");
        when(paymentService.createPending(any(), any())).thenAnswer(call ->
                PaymentTransaction.create(call.getArgument(0), "BE24-01-TEST",
                        call.getArgument(1), Instant.now()));
    }

    private void givenExpoStatus(String status) {
        when(expoClient.getExpo(anyLong())).thenReturn(new ExpoSummary(EXPO_ID, OWNER_ID, status));
    }

    private Long futureRound() {
        return rounds.save(Round.create(EXPO_ID,
                now.plusSeconds(86400), now.plusSeconds(90000), CAPACITY, FEE, now)).getId();
    }

    private Reservation confirm(long userId, int headcount) {
        Reservation reservation = reservationService.create(roundId,
                new AuthenticatedUser(userId, "USER"),
                new CreateReservationRequest(headcount, "예약자" + userId, "01012345678")).reservation();
        when(paymentService.confirm(reservation.getId()))
                .thenReturn(PaymentApprovalResult.success(reservation.getAmount()));
        paymentTransition.confirm(reservation.getId(), new AuthenticatedUser(userId, "USER"));
        return reservation;
    }

    private boolean deleted(Long id) {
        return rounds.findById(id).orElseThrow().isDeleted();
    }

    @Test
    @DisplayName("예약이 없으면 삭제된다 - 행은 남고 deleted_at 만 채워진다")
    void softDeletesWhenNoReservation() {
        futureRound();   // 마지막 회차가 아니게 하나 더 둔다

        roundService.delete(EXPO_ID, roundId, OWNER);

        assertThat(deleted(roundId)).isTrue();
        assertThat(rounds.findById(roundId)).isPresent();   // 하드 삭제가 아니다
    }

    @Test
    @DisplayName("활성 예약이 1건이라도 있으면 409 이고 삭제되지 않는다")
    void rejectsWhenReservationExists() {
        futureRound();
        confirm(1L, 2);

        assertThatThrownBy(() -> roundService.delete(EXPO_ID, roundId, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ROUND_HAS_RESERVATIONS));

        assertThat(deleted(roundId)).isFalse();
    }

    @Test
    @DisplayName("예약이 전부 취소되면 삭제할 수 있다 - FK 때문에 하드 삭제로는 불가능한 경우다")
    void allowsDeleteAfterAllReservationsCancelled() {
        futureRound();
        Reservation reservation = confirm(1L, 2);
        cancelService.cancel(reservation.getId(), new AuthenticatedUser(1L, "USER"));

        roundService.delete(EXPO_ID, roundId, OWNER);

        assertThat(deleted(roundId)).isTrue();
        // 취소 이력은 그대로 남는다.
        assertThat(reservations.findById(reservation.getId())).isPresent();
    }

    @Test
    @DisplayName("이미 시작한 회차는 삭제할 수 없다 (409)")
    void rejectsStartedRound() {
        // 생성 시각을 과거로 두면 startsAt 이 과거인 회차를 만들 수 있다.
        Long started = rounds.save(Round.create(EXPO_ID, now.minusSeconds(3600),
                now.plusSeconds(3600), CAPACITY, FEE, now.minusSeconds(7200))).getId();

        assertThatThrownBy(() -> roundService.delete(EXPO_ID, started, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ROUND_ALREADY_STARTED));

        assertThat(deleted(started)).isFalse();
    }

    // ---- 마지막 회차와 자동 비공개 ----

    @Test
    @DisplayName("마지막 회차를 지우면 박람회를 비공개로 바꾼다")
    void unpublishesExpoWhenDeletingLastRound() {
        roundService.delete(EXPO_ID, roundId, OWNER);

        verify(expoClient).unpublish(EXPO_ID);
        assertThat(deleted(roundId)).isTrue();
    }

    @Test
    @DisplayName("마지막이 아니면 비공개로 바꾸지 않는다")
    void keepsExpoPublishedWhenOtherRoundsRemain() {
        futureRound();

        roundService.delete(EXPO_ID, roundId, OWNER);

        verify(expoClient, never()).unpublish(anyLong());
    }

    @Test
    @DisplayName("이미 비공개인 박람회의 마지막 회차는 그냥 삭제된다")
    void skipsUnpublishWhenExpoAlreadyHidden() {
        givenExpoStatus("HIDDEN");

        roundService.delete(EXPO_ID, roundId, OWNER);

        verify(expoClient, never()).unpublish(anyLong());
        assertThat(deleted(roundId)).isTrue();
    }

    @Test
    @DisplayName("비공개 전환이 실패하면 삭제가 롤백된다 - 회차 0개인 공개 박람회를 만들지 않는다")
    void doesNotDeleteWhenUnpublishFails() {
        doThrow(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "expo-service unavailable"))
                .when(expoClient).unpublish(EXPO_ID);

        assertThatThrownBy(() -> roundService.delete(EXPO_ID, roundId, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));

        assertThat(deleted(roundId)).isFalse();
    }

    @Test
    @DisplayName("예약이 있는 마지막 회차는 409 이고 박람회도 공개 상태 그대로다 - 비공개 호출 자체를 하지 않는다")
    void keepsExpoPublishedWhenLastRoundHasReservation() {
        confirm(1L, 2);

        assertThatThrownBy(() -> roundService.delete(EXPO_ID, roundId, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ROUND_HAS_RESERVATIONS));

        verify(expoClient, never()).unpublish(anyLong());
        assertThat(deleted(roundId)).isFalse();
    }

    // ---- 소유권 ----

    @Test
    @DisplayName("다른 주최자면 403 이고 삭제되지 않는다")
    void rejectsOtherOrganizer() {
        assertThatThrownBy(() -> roundService.delete(EXPO_ID, roundId, OTHER_ORGANIZER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThat(deleted(roundId)).isFalse();
    }

    @Test
    @DisplayName("이미 삭제된 회차를 다시 지우면 404")
    void rejectsAlreadyDeletedRound() {
        futureRound();
        roundService.delete(EXPO_ID, roundId, OWNER);

        assertThatThrownBy(() -> roundService.delete(EXPO_ID, roundId, OWNER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
