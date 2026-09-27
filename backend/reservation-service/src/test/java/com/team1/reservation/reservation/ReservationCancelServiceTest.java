package com.team1.reservation.reservation;

import com.team1.payment.PaymentApprovalResult;
import com.team1.payment.PaymentService;
import com.team1.payment.PaymentStatus;
import com.team1.payment.PaymentTransaction;
import com.team1.payment.PaymentTransactionRepository;
import com.team1.reservation.client.TicketClient;
import com.team1.reservation.client.TicketDetail;
import com.team1.reservation.common.ApiException;
import com.team1.reservation.common.ErrorCode;
import com.team1.reservation.reservation.dto.CancelReservationResponse;
import com.team1.reservation.reservation.entity.RefundState;
import com.team1.reservation.reservation.entity.Reservation;
import com.team1.reservation.reservation.entity.ReservationStatus;
import com.team1.reservation.reservation.service.ReservationCancelService;
import com.team1.reservation.reservation.support.TicketDispatchStub;
import com.team1.reservation.round.entity.Round;
import com.team1.reservation.round.repository.RoundRepository;
import com.team1.reservation.reservation.repository.ReservationRepository;
import com.team1.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * #83 취소·환불 규칙. 취소는 회차 시작 전까지, 전액 환불은 시작 24시간 전까지.
 * 그 사이에 취소하면 취소는 되지만 환불은 없다.
 */
class ReservationCancelServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T04:00:00Z");
    private static final Long RESERVATION_ID = 42L;
    private static final Long ROUND_ID = 7L;
    private static final Long EXPO_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final int HEADCOUNT = 2;
    private static final int AMOUNT = 20000;
    private static final AuthenticatedUser MEMBER = new AuthenticatedUser(USER_ID, "USER");
    private static final int MAX_REFUND_ATTEMPTS = 6;

    private ReservationRepository reservations;
    private RoundRepository rounds;
    private PaymentTransactionRepository payments;
    private PaymentService paymentService;
    private TicketClient ticketClient;
    private ReservationCancelService service;
    private PaymentTransaction payment;

    @BeforeEach
    void setUp() {
        reservations = mock(ReservationRepository.class);
        rounds = mock(RoundRepository.class);
        payments = mock(PaymentTransactionRepository.class);
        paymentService = mock(PaymentService.class);
        ticketClient = mock(TicketClient.class);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ReservationCancelService(reservations, rounds, payments, paymentService,
                TicketDispatchStub.notifier(ticketClient, clock), ticketClient, clock,
                Duration.ZERO, Duration.ofDays(1), MAX_REFUND_ATTEMPTS);

        when(reservations.cancelIfActive(anyLong(), any())).thenReturn(1);
    }

    /** 회차 시작까지 남은 시간을 정해 상황을 만든다. */
    private Reservation givenRoundStartingIn(Duration untilStart, int fee) {
        Instant startsAt = NOW.plus(untilStart);
        Round round = Round.create(EXPO_ID, startsAt, startsAt.plusSeconds(7200), 100, fee,
                NOW.minus(Duration.ofDays(30)));
        when(rounds.findById(ROUND_ID)).thenReturn(Optional.of(round));

        Reservation reservation = Reservation.create("R-4K7Q-W2M8", ROUND_ID, EXPO_ID, USER_ID,
                "홍길동", "01012345678", HEADCOUNT, fee * HEADCOUNT, NOW.minus(Duration.ofDays(3)));
        ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
        reservation.confirm(NOW.minus(Duration.ofDays(3)));
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reservation));
        return reservation;
    }

    private void givenPayment(PaymentStatus status) {
        givenPayment(status, 0);
    }

    private void givenPayment(PaymentStatus status, int attempts) {
        payment = PaymentTransaction.create(RESERVATION_ID, "BE24-01-TEST", AMOUNT, NOW);
        ReflectionTestUtils.setField(payment, "status", status);
        ReflectionTestUtils.setField(payment, "attempts", attempts);
        when(payments.findByRefId(RESERVATION_ID)).thenReturn(Optional.of(payment));
    }

    /** 실제 모듈은 같은 영속성 컨텍스트의 행을 직접 바꾼다. Mock 도 그렇게 흉내낸다. */
    private void whenRefundedBecomes(PaymentStatus after) {
        whenRefundedBecomes(after, 1);
    }


    @Test
    @DisplayName("이미 체크인한 예약은 취소되지 않고 정원도 환불도 건드리지 않는다")
    void 체크인한_예약은_취소할_수_없다() {
        // 회차 3일 뒤 = 환불 창 안. 막지 않으면 관람을 마친 사람에게 전액 환불이 나간다.
        givenRoundStartingIn(Duration.ofDays(3), 10000);
        givenPayment(PaymentStatus.PAID);
        when(ticketClient.findTicketFailClosed(RESERVATION_ID))
                .thenReturn(new TicketDetail(9L, "tok", NOW, "USED"));

        assertThatThrownBy(() -> service.cancel(RESERVATION_ID, MEMBER))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ALREADY_CHECKED_IN);

        verify(reservations, never()).cancelIfActive(anyLong(), any());
        verify(rounds, never()).release(anyLong(), anyInt());
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("티켓 상태를 확인할 수 없으면 취소를 거절한다(fail-closed)")
    void 티켓_조회에_실패하면_취소하지_않는다() {
        // 모른 채 취소하는 대가는 환불과 정원 이중 판매고, 거절하는 대가는 잠시 취소하지 못하는 것이다.
        givenRoundStartingIn(Duration.ofDays(3), 10000);
        when(ticketClient.findTicketFailClosed(RESERVATION_ID))
                .thenThrow(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "ticket-service unavailable"));

        assertThatThrownBy(() -> service.cancel(RESERVATION_ID, MEMBER))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);

        verify(reservations, never()).cancelIfActive(anyLong(), any());
        verify(rounds, never()).release(anyLong(), anyInt());
    }

    private void whenRefundedBecomes(PaymentStatus after, int attempts) {
        doAnswer(call -> {
            ReflectionTestUtils.setField(payment, "status", after);
            ReflectionTestUtils.setField(payment, "attempts", attempts);
            return null;
        }).when(paymentService).cancel(anyLong(), anyString());
    }

    // ── 환불 판정 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("회차 시작 24시간 전보다 이르면 전액 환불하고 REFUNDED 를 내려준다")
    void refundsInFullBeforeWindow() {
        givenRoundStartingIn(Duration.ofDays(3), 10000);
        givenPayment(PaymentStatus.PAID);
        whenRefundedBecomes(PaymentStatus.CANCELLED);

        CancelReservationResponse response = service.cancel(RESERVATION_ID, MEMBER);

        assertThat(response.refundState()).isEqualTo(RefundState.REFUNDED);
        verify(paymentService).cancel(RESERVATION_ID, "user cancellation");
    }

    @Test
    @DisplayName("환불 요청이 PG 에 닿지 못하면 REFUND_PENDING - 아직 돈이 돌아가지 않았다")
    void reportsRefundPendingWhenPgFailed() {
        givenRoundStartingIn(Duration.ofDays(3), 10000);
        givenPayment(PaymentStatus.PAID);
        // 모듈은 예외를 던지지 않고 REFUND_FAILED 로 기록한다.
        whenRefundedBecomes(PaymentStatus.REFUND_FAILED);

        assertThat(service.cancel(RESERVATION_ID, MEMBER).refundState())
                .isEqualTo(RefundState.REFUND_PENDING);
    }

    @Test
    @DisplayName("재시도 상한을 넘기면 REFUND_UNRESOLVED - 끝나지 않는 \"처리 중\" 을 보여주지 않는다")
    void reportsUnresolvedAfterRetryLimit() {
        givenRoundStartingIn(Duration.ofDays(3), 10000);
        givenPayment(PaymentStatus.PAID);
        whenRefundedBecomes(PaymentStatus.REFUND_FAILED, MAX_REFUND_ATTEMPTS);

        assertThat(service.cancel(RESERVATION_ID, MEMBER).refundState())
                .isEqualTo(RefundState.REFUND_UNRESOLVED);
    }

    @Test
    @DisplayName("상한 직전까지는 아직 REFUND_PENDING 이다")
    void staysPendingJustBelowLimit() {
        givenRoundStartingIn(Duration.ofDays(3), 10000);
        givenPayment(PaymentStatus.PAID);
        whenRefundedBecomes(PaymentStatus.REFUND_FAILED, MAX_REFUND_ATTEMPTS - 1);

        assertThat(service.cancel(RESERVATION_ID, MEMBER).refundState())
                .isEqualTo(RefundState.REFUND_PENDING);
    }

    @Test
    @DisplayName("24시간 안으로 들어오면 취소는 되지만 환불은 없다 - NOT_REFUNDABLE")
    void cancelsWithoutRefundInsideWindow() {
        givenRoundStartingIn(Duration.ofHours(5), 10000);
        givenPayment(PaymentStatus.PAID);

        CancelReservationResponse response = service.cancel(RESERVATION_ID, MEMBER);

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.refundState()).isEqualTo(RefundState.NOT_REFUNDABLE);
        verify(paymentService, never()).cancel(anyLong(), anyString());
    }

    @Test
    @DisplayName("경계는 정확히 24시간 - 24시간 0초 전은 아직 환불된다")
    void refundsExactlyAtBoundary() {
        givenRoundStartingIn(Duration.ofDays(1), 10000);
        givenPayment(PaymentStatus.PAID);
        whenRefundedBecomes(PaymentStatus.CANCELLED);

        assertThat(service.cancel(RESERVATION_ID, MEMBER).refundState())
                .isEqualTo(RefundState.REFUNDED);
    }

    @Test
    @DisplayName("무료 회차는 환불 단계를 건너뛴다")
    void skipsRefundForFreeRound() {
        givenRoundStartingIn(Duration.ofDays(3), 0);

        assertThat(service.cancel(RESERVATION_ID, MEMBER).refundState())
                .isEqualTo(RefundState.NOT_APPLICABLE);
        verifyNoInteractions(paymentService);
        verify(ticketClient).revokeTicket(RESERVATION_ID);
    }

    @Test
    @DisplayName("결제가 PAID 가 아니면 환불하지 않는다 - 돌려줄 돈이 없다")
    void skipsRefundWhenPaymentNotPaid() {
        givenRoundStartingIn(Duration.ofDays(3), 10000);
        givenPayment(PaymentStatus.PENDING);

        assertThat(service.cancel(RESERVATION_ID, MEMBER).refundState())
                .isEqualTo(RefundState.NOT_APPLICABLE);
        verify(paymentService, never()).cancel(anyLong(), anyString());
    }

    // ── 기한·전이 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("회차가 시작된 뒤에는 400 CANCELLATION_DEADLINE_PASSED - 정원도 건드리지 않는다")
    void rejectsAfterRoundStart() {
        givenRoundStartingIn(Duration.ofHours(-1), 10000);

        assertThatThrownBy(() -> service.cancel(RESERVATION_ID, MEMBER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CANCELLATION_DEADLINE_PASSED));

        verify(reservations, never()).cancelIfActive(anyLong(), any());
        verify(rounds, never()).release(anyLong(), anyInt());
    }

    @Test
    @DisplayName("전이에 성공한 경우에만 정원을 돌려준다")
    void releasesCapacityOnlyOnTransition() {
        givenRoundStartingIn(Duration.ofDays(3), 10000);
        givenPayment(PaymentStatus.PAID);
        whenRefundedBecomes(PaymentStatus.CANCELLED);

        service.cancel(RESERVATION_ID, MEMBER);

        verify(rounds).release(ROUND_ID, HEADCOUNT);
    }

    @Test
    @DisplayName("만료 배치가 먼저 끝냈으면 409 이고 정원을 두 번 반환하지 않는다")
    void rejectsWhenExpired() {
        Reservation reservation = givenRoundStartingIn(Duration.ofDays(3), 10000);
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.EXPIRED);
        when(reservations.cancelIfActive(anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(RESERVATION_ID, MEMBER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        verify(rounds, never()).release(anyLong(), anyInt());
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("이미 취소된 예약은 멱등 200 - 정원을 두 번 반환하지 않는다")
    void isIdempotentWhenAlreadyCancelled() {
        Reservation reservation = givenRoundStartingIn(Duration.ofDays(3), 10000);
        reservation.cancel(NOW.minus(Duration.ofHours(1)));
        when(reservations.cancelIfActive(anyLong(), any())).thenReturn(0);

        CancelReservationResponse response = service.cancel(RESERVATION_ID, MEMBER);

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.cancelledAt()).isEqualTo(NOW.minus(Duration.ofHours(1)));
        verify(rounds, never()).release(anyLong(), anyInt());
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("멱등 응답의 환불 상태도 결제에서 읽는다 - 다시 환불하지 않는다")
    void readsRefundStateOnIdempotentReplay() {
        Reservation reservation = givenRoundStartingIn(Duration.ofDays(3), 10000);
        reservation.cancel(NOW.minus(Duration.ofHours(1)));
        when(reservations.cancelIfActive(anyLong(), any())).thenReturn(0);
        givenPayment(PaymentStatus.REFUND_FAILED, 2);

        assertThat(service.cancel(RESERVATION_ID, MEMBER).refundState())
                .isEqualTo(RefundState.REFUND_PENDING);
        verifyNoInteractions(paymentService);
    }

    // ── 권한 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("취소하면 티켓 무효화를 통지한다")
    void notifiesTicketRevoke() {
        givenRoundStartingIn(Duration.ofDays(3), 10000);
        givenPayment(PaymentStatus.PAID);
        whenRefundedBecomes(PaymentStatus.CANCELLED);

        service.cancel(RESERVATION_ID, MEMBER);

        verify(ticketClient).revokeTicket(RESERVATION_ID);
    }

    // ── 결제창 이후 PENDING 취소 ─────────────────────────────────────────────

    /** 결제창에서 결제는 끝났지만 웹훅·확정 호출이 아직 안 와서 예약도 결제도 PENDING 인 상황. */
    private void givenPendingReservation() {
        Reservation reservation = givenRoundStartingIn(Duration.ofHours(2), 10000);
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.PENDING);
        ReflectionTestUtils.setField(reservation, "confirmedAt", null);
        givenPayment(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("PENDING 취소 시 PG 가 PAID 면 환불 기한과 무관하게 환불한다 - 확정된 적 없는 예약이다")
    void refundsPaidPendingReservationOnCancel() {
        givenPendingReservation();
        doAnswer(call -> {
            ReflectionTestUtils.setField(payment, "status", PaymentStatus.PAID);
            return PaymentApprovalResult.success(AMOUNT);
        }).when(paymentService).confirm(RESERVATION_ID);
        whenRefundedBecomes(PaymentStatus.CANCELLED);

        CancelReservationResponse response = service.cancel(RESERVATION_ID, MEMBER);

        assertThat(response.refundState()).isEqualTo(RefundState.REFUNDED);
        verify(paymentService).cancel(RESERVATION_ID, "user cancellation");
    }

    @Test
    @DisplayName("PENDING 취소 시 PG 결과를 모르면(결제창만 닫은 경우 포함) 지금처럼 환불 없이 취소한다")
    void cancelsPendingWithoutRefundWhenPgUnknown() {
        givenPendingReservation();
        when(paymentService.confirm(RESERVATION_ID)).thenReturn(PaymentApprovalResult.unknown("PG 거래없음"));

        CancelReservationResponse response = service.cancel(RESERVATION_ID, MEMBER);

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.refundState()).isEqualTo(RefundState.NOT_APPLICABLE);
        verify(paymentService, never()).cancel(anyLong(), anyString());
    }

    @Test
    @DisplayName("남의 예약은 403 이 아니라 404 다 - 예약의 존재 자체를 흘리지 않는다")
    void rejectsOtherMembersReservation() {
        givenRoundStartingIn(Duration.ofDays(3), 10000);

        assertThatThrownBy(() -> service.cancel(RESERVATION_ID, new AuthenticatedUser(999L, "USER")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(reservations, never()).cancelIfActive(anyLong(), any());
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("USER 가 아닌 Role 은 403, 미인증은 401")
    void rejectsNonMember() {
        assertThatThrownBy(() -> service.cancel(RESERVATION_ID, new AuthenticatedUser(1L, "ORGANIZER")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> service.cancel(RESERVATION_ID, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));

        verify(reservations, never()).findById(anyLong());
    }
}
