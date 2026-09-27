package com.team1.reservation.reservation.service;

import com.team1.payment.PaymentApprovalOutcome;
import com.team1.payment.PaymentApprovalResult;
import com.team1.payment.PaymentService;
import com.team1.payment.PaymentStatus;
import com.team1.payment.PaymentTransaction;
import com.team1.payment.PaymentTransactionRepository;
import com.team1.reservation.client.TicketClient;
import com.team1.reservation.client.TicketDetail;
import com.team1.reservation.common.ApiException;
import com.team1.reservation.common.ErrorCode;
import com.team1.reservation.common.TraceId;
import com.team1.reservation.reservation.dto.CancelReservationResponse;
import com.team1.reservation.reservation.entity.RefundState;
import com.team1.reservation.reservation.entity.Reservation;
import com.team1.reservation.reservation.entity.ReservationStatus;
import com.team1.reservation.reservation.repository.ReservationRepository;
import com.team1.reservation.round.entity.Round;
import com.team1.reservation.round.repository.RoundRepository;
import com.team1.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 사용자 예약 취소(#83).
 *
 * <p>기한이 둘이고 서로 다르다.
 * <ul>
 *   <li><b>취소</b>는 회차 시작 전까지 가능하다.</li>
 *   <li><b>전액 환불</b>은 회차 시작 24시간 전까지 취소한 경우에만 이뤄진다.</li>
 * </ul>
 *
 * <p>그 사이 구간에서 취소하면 예약은 {@code CANCELLED} 인데 결제는 {@code PAID} 로 남는다.
 * 오류가 아니라 정상 상태다.
 *
 * <p>기준은 <b>예약한 회차</b>의 시작 시각이다. 박람회에는 날짜 컬럼이 없고 개최 시각은
 * 전부 회차에 있으며, 예약자에게 "박람회 날짜" 는 곧 자기 회차 날짜다.
 */
@Service
public class ReservationCancelService {

    private static final Logger log = LoggerFactory.getLogger(ReservationCancelService.class);

    static final String ROLE_MEMBER = "USER";

    private static final String TICKET_USED = "USED";

    private final ReservationRepository reservations;
    private final RoundRepository rounds;
    private final PaymentTransactionRepository payments;
    private final PaymentService paymentService;
    private final TicketIssueNotifier ticketNotifier;
    private final TicketClient ticketClient;
    private final Clock clock;
    private final Duration deadlineBeforeStart;
    private final Duration refundWindow;
    private final int refundMaxAttempts;

    public ReservationCancelService(ReservationRepository reservations,
                                    RoundRepository rounds,
                                    PaymentTransactionRepository payments,
                                    PaymentService paymentService,
                                    TicketIssueNotifier ticketNotifier,
                                    TicketClient ticketClient,
                                    Clock clock,
                                    @Value("${reservation.cancellation.deadline-before-start}") Duration deadlineBeforeStart,
                                    @Value("${reservation.cancellation.refund-window}") Duration refundWindow,
                                    @Value("${scheduler.refund-retry.max-attempts}") int refundMaxAttempts) {
        this.reservations = reservations;
        this.rounds = rounds;
        this.payments = payments;
        this.paymentService = paymentService;
        this.ticketNotifier = ticketNotifier;
        this.ticketClient = ticketClient;
        this.clock = clock;
        this.deadlineBeforeStart = deadlineBeforeStart;
        this.refundWindow = refundWindow;
        this.refundMaxAttempts = refundMaxAttempts;
    }

    @Transactional
    public CancelReservationResponse cancel(Long reservationId, AuthenticatedUser user) {
        requireMember(user);

        Reservation reservation = reservations.findById(reservationId)
                .filter(r -> Objects.equals(r.getUserId(), user.userId()))
                // 남의 예약도 404 다. 403 을 주면 그 번호의 예약이 존재한다는 사실이 드러나고,
                // reservationId 는 연속된 값이라 훑기 쉽다.
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "reservation not found: " + reservationId));

        Round round = rounds.findById(reservation.getRoundId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "round not found: " + reservation.getRoundId()));

        Instant now = clock.instant();
        Long roundId = reservation.getRoundId();
        int headcount = reservation.getHeadcount();
        int amount = reservation.getAmount();

        if (!now.isBefore(round.getStartsAt().minus(deadlineBeforeStart))) {
            throw new ApiException(ErrorCode.CANCELLATION_DEADLINE_PASSED,
                    "cancellation deadline has passed for round " + roundId);
        }

        // PENDING 은 아직 티켓이 없으므로 묻지 않는다. 결제창을 닫은 취소가 이 경로를 자주 탄다.
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            requireNotCheckedIn(reservationId);
        }

        // 결제는 끝났는데 웹훅·확정 호출이 아직 안 온 PENDING 일 수 있다. 로컬 결제가 PENDING 인 채로
        // 취소하면 환불 없이 끝나고, 뒤늦은 웹훅은 이미 취소된 예약이라 반영되지 않는다.
        // 만료 스케줄러처럼 PG 를 먼저 조회해 결제 상태만 맞춰 둔다(예약 확정은 하지 않는다).
        boolean wasPending = reservation.getStatus() == ReservationStatus.PENDING;
        if (wasPending) {
            syncPendingPayment(reservationId);
        }

        // 순서가 중요하다. 전이가 먼저다 - 0 행이면 만료 배치나 웹훅, 또는 동시 요청이
        // 먼저 끝낸 것이고, 그때 정원을 반환하면 두 번 돌려주게 된다.
        if (reservations.cancelIfActive(reservationId, now) == 0) {
            return alreadyFinished(reservationId);
        }
        rounds.release(roundId, headcount);

        RefundState refundState = refundIfEligible(reservationId, amount, round, now, wasPending);

        // 환불 여부와 무관하게 무효화한다. 환불을 못 받아도 입장은 막아야 한다.
        ticketNotifier.notifyRevoked(reservation);

        return new CancelReservationResponse(reservationId, ReservationStatus.CANCELLED.name(),
                refundState, now);
    }

    /**
     * 이미 입장한 예약은 취소할 수 없다. 취소하면 관람을 마친 사람에게 환불하고 그 자리를 다시 판다.
     *
     * <p>티켓 무효화는 USED 를 조용히 건너뛰므로(계약 1-2 의 멱등 규칙) 통지로는 이 사실을 알 수 없다.
     * 취소 전에 직접 물어봐야 한다.
     *
     * <p>조회에 실패하면 취소를 거절한다(fail-closed). 모른 채 취소하는 대가는 환불과 정원 이중 판매고,
     * 거절하는 대가는 잠시 취소하지 못하는 것이다.
     */
    private void requireNotCheckedIn(Long reservationId) {
        TicketDetail ticket = ticketClient.findTicketFailClosed(reservationId);

        if (ticket != null && TICKET_USED.equals(ticket.status())) {
            log.info("cancellation rejected: already checked in reservationId={} traceId={}",
                    reservationId, TraceId.get());
            throw new ApiException(ErrorCode.ALREADY_CHECKED_IN,
                    "reservation " + reservationId + " has already been checked in");
        }
    }

    /**
     * 전이에 실패한 경우의 응답. 이미 CANCELLED 면 멱등 200 이고, 그 외 종료 상태는 409 다.
     *
     * <p>취소는 사용자가 다시 누를 수 있는 동작이라 두 번째 요청이 오류로 보이면 안 된다.
     * 정원은 이미 첫 요청이 반환했으므로 여기서 건드리지 않는다.
     */
    private CancelReservationResponse alreadyFinished(Long reservationId) {
        Reservation current = reservations.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "reservation not found: " + reservationId));

        if (current.getStatus() != ReservationStatus.CANCELLED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "reservation is " + current.getStatus());
        }

        // 환불 상태는 결제에서 읽는다. 다시 환불을 시도하지는 않는다.
        RefundState refundState = RefundState.of(current.getStatus(),
                payments.findByRefId(reservationId).orElse(null), refundMaxAttempts);

        return new CancelReservationResponse(reservationId, ReservationStatus.CANCELLED.name(),
                refundState, current.getCancelledAt());
    }

    /** 환불을 시도하고 그 결과를 표시값으로 돌려준다. */
    /**
     * PG 가 PAID 라고 하면 로컬 결제를 PAID 로 맞춘다. 모름(결제창만 열고 닫은 경우도 여기다)이면
     * 아무것도 바꾸지 않고 지금처럼 환불 없이 취소한다 - 막으면 결제창을 닫은 취소가 전부 실패한다.
     */
    private void syncPendingPayment(Long reservationId) {
        boolean pending = payments.findByRefId(reservationId)
                .map(p -> p.getStatus() == PaymentStatus.PENDING)
                .orElse(false);
        if (!pending) {
            return;
        }
        PaymentApprovalResult result = paymentService.confirm(reservationId);
        if (result.outcome() == PaymentApprovalOutcome.AMOUNT_MISMATCH) {
            log.warn("pending cancel with amount mismatch reservationId={} traceId={}",
                    reservationId, TraceId.get());
        }
    }

    /** @param neverConfirmed 확정된 적 없는 예약이면 환불 기한과 무관하게 돌려준다 - 받은 것이 없다. */
    private RefundState refundIfEligible(Long reservationId, int amount, Round round, Instant now,
                                         boolean neverConfirmed) {
        if (amount == 0) {
            return RefundState.NOT_APPLICABLE;
        }

        Optional<PaymentTransaction> found = payments.findByRefId(reservationId);
        if (found.isEmpty()) {
            return RefundState.NOT_APPLICABLE;
        }
        PaymentTransaction payment = found.get();

        if (payment.getStatus() != PaymentStatus.PAID) {
            // 결제가 끝나지 않았거나 실패했으면 돌려줄 돈이 없다.
            return RefundState.of(ReservationStatus.CANCELLED, payment, refundMaxAttempts);
        }

        if (!neverConfirmed && now.isAfter(round.getStartsAt().minus(refundWindow))) {
            // 예약 CANCELLED · 결제 PAID 조합은 정상이다. 기한이 지나 환불하지 않은 것이다.
            log.info("cancelled without refund reservationId={} startsAt={} now={} traceId={}",
                    reservationId, round.getStartsAt(), now, TraceId.get());
            return RefundState.NOT_REFUNDABLE;
        }

        // 모듈이 PG 무응답을 REFUND_FAILED 로 기록하고 예외를 던지지 않는다. 취소 자체는
        // 되돌리지 않는 것이 맞다 - 되돌리는 사이 다른 회원이 그 자리를 가져갔을 수 있다.
        paymentService.cancel(reservationId, "user cancellation");

        // 모듈이 같은 영속성 컨텍스트의 이 행을 갱신했으므로 상태를 다시 읽으면 결과가 보인다.
        return RefundState.of(ReservationStatus.CANCELLED, payment, refundMaxAttempts);
    }

    private void requireMember(AuthenticatedUser user) {
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "authentication required");
        }
        if (!ROLE_MEMBER.equals(user.role())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "USER role required");
        }
    }
}
