package com.team1.reservation.reservation.service;

import com.team1.reservation.client.IssueTicketCommand;
import com.team1.reservation.client.IssuedTicket;
import com.team1.reservation.client.TicketClient;
import com.team1.reservation.common.TraceId;
import com.team1.reservation.reservation.entity.ReservationStatus;
import com.team1.reservation.reservation.entity.RetryPolicy;
import com.team1.reservation.reservation.entity.TicketDispatch;
import com.team1.reservation.reservation.entity.TicketDispatchStatus;
import com.team1.reservation.reservation.entity.TicketDispatchType;
import com.team1.reservation.reservation.repository.ReservationRepository;
import com.team1.reservation.reservation.repository.TicketDispatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * 통지 1건을 실제로 보낸다(#79). 즉시 시도와 재시도 배치가 모두 이 경로를 탄다.
 *
 * <p><b>절대 예외를 던지지 않는다.</b> 즉시 시도는 예약 확정 직후에 불리므로 여기서 예외가
 * 새어 나가면 fail-open 이 깨지고, 배치에서는 한 건이 나머지를 멈춘다.
 */
@Component
public class TicketDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TicketDispatcher.class);

    private final TicketDispatchRepository queue;
    private final ReservationRepository reservations;
    private final TicketClient ticketClient;
    private final Clock clock;
    private final RetryPolicy issuePolicy;
    private final RetryPolicy revokePolicy;

    // 생성자가 둘이므로 Spring 이 쓸 쪽을 명시한다. 없으면 컨텍스트가 뜨지 않는다.
    @Autowired
    public TicketDispatcher(TicketDispatchRepository queue,
                            ReservationRepository reservations,
                            TicketClient ticketClient,
                            Clock clock,
                            @Value("${scheduler.ticket-dispatch.issue.max-attempts}") int issueMaxAttempts,
                            @Value("${scheduler.ticket-dispatch.issue.backoff}") Duration issueBackoff,
                            @Value("${scheduler.ticket-dispatch.issue.max-backoff}") Duration issueMaxBackoff,
                            @Value("${scheduler.ticket-dispatch.revoke.max-attempts}") int revokeMaxAttempts,
                            @Value("${scheduler.ticket-dispatch.revoke.backoff}") Duration revokeBackoff,
                            @Value("${scheduler.ticket-dispatch.revoke.max-backoff}") Duration revokeMaxBackoff) {
        this(queue, reservations, ticketClient, clock,
                new RetryPolicy(issueMaxAttempts, issueBackoff, issueMaxBackoff),
                new RetryPolicy(revokeMaxAttempts, revokeBackoff, revokeMaxBackoff));
    }

    // Test 가 정책을 직접 넣을 수 있게 열어둔다.
    public TicketDispatcher(TicketDispatchRepository queue, ReservationRepository reservations,
                            TicketClient ticketClient, Clock clock,
                            RetryPolicy issuePolicy, RetryPolicy revokePolicy) {
        this.queue = queue;
        this.reservations = reservations;
        this.ticketClient = ticketClient;
        this.clock = clock;
        this.issuePolicy = issuePolicy;
        this.revokePolicy = revokePolicy;
    }

    /** 성공하면 true. 실패·이미 처리됨은 false 이고, 어느 경우에도 예외를 던지지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean dispatch(Long dispatchId) {
        TicketDispatch dispatch = queue.findById(dispatchId).orElse(null);
        if (dispatch == null || !dispatch.isPending()) {
            return false;
        }

        // 발급 재시도가 밀린 사이 취소됐을 수 있다. 무효화는 티켓이 없어 이미 성공으로 끝났으므로,
        // 여기서 보내면 취소된 예약에 유효한 티켓이 생긴다.
        if (dispatch.getType() == TicketDispatchType.ISSUE && !isStillConfirmed(dispatch.getReservationId())) {
            dispatch.abandon("reservation is no longer CONFIRMED", clock.instant());
            log.info("ticket ISSUE abandoned reservationId={} traceId={}",
                    dispatch.getReservationId(), TraceId.get());
            return false;
        }

        try {
            Long ticketId = send(dispatch);

            dispatch.succeeded(ticketId, clock.instant());
            log.info("ticket {} ok reservationId={} ticketId={} attempts={} traceId={}",
                    dispatch.getType(), dispatch.getReservationId(), ticketId,
                    dispatch.getAttempts(), TraceId.get());
            return true;

        } catch (RuntimeException e) {
            dispatch.failed(e.toString(), policyFor(dispatch.getType()), clock.instant());
            logFailure(dispatch, e);
            return false;
        }
    }

    // 행을 잠가 둔 채 발급까지 마친다. 그동안 들어온 취소는 기다렸다가 무효화를 뒤에 보낸다.
    private boolean isStillConfirmed(Long reservationId) {
        return reservations.findByIdForUpdate(reservationId)
                .map(r -> r.getStatus() == ReservationStatus.CONFIRMED)
                .orElse(false);
    }

    // 무효화 실패는 "취소된 예약으로 입장 가능" 이라 발급 실패보다 나쁘다. 훨씬 오래 시도한다.
    private RetryPolicy policyFor(TicketDispatchType type) {
        return type == TicketDispatchType.REVOKE ? revokePolicy : issuePolicy;
    }

    /** 무효화는 돌려받을 ticketId 가 없다(계약상 204). null 을 그대로 기록한다. */
    private Long send(TicketDispatch dispatch) {
        if (dispatch.getType() == TicketDispatchType.REVOKE) {
            ticketClient.revokeTicket(dispatch.getReservationId());
            return null;
        }
        IssuedTicket issued = ticketClient.issueTicket(new IssueTicketCommand(
                dispatch.getReservationId(), dispatch.getReservationNo(), dispatch.getExpoId(),
                dispatch.getRoundId(), dispatch.getUserId(), dispatch.getHeadcount()));
        return issued.ticketId();
    }

    private void logFailure(TicketDispatch dispatch, RuntimeException e) {
        if (dispatch.getStatus() == TicketDispatchStatus.GAVE_UP) {
            // 자동 회수를 포기했다. CS 문의가 들어오면 이 로그로 찾는다.
            log.error("TICKET_DISPATCH_GAVE_UP type={} reservationId={} attempts={} traceId={} reason={}",
                    dispatch.getType(), dispatch.getReservationId(), dispatch.getAttempts(),
                    TraceId.get(), e.toString());
            return;
        }
        log.warn("TICKET_DISPATCH_FAILED type={} reservationId={} attempts={} nextAttemptAt={} traceId={} reason={}",
                dispatch.getType(), dispatch.getReservationId(), dispatch.getAttempts(),
                dispatch.getNextAttemptAt(), TraceId.get(), e.toString());
    }
}
