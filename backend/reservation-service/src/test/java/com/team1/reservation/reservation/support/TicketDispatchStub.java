package com.team1.reservation.reservation.support;

import com.team1.reservation.client.TicketClient;
import com.team1.reservation.config.AfterCommitExecutor;
import com.team1.reservation.reservation.entity.Reservation;
import com.team1.reservation.reservation.entity.ReservationStatus;
import com.team1.reservation.reservation.entity.RetryPolicy;
import com.team1.reservation.reservation.entity.TicketDispatch;
import com.team1.reservation.reservation.repository.ReservationRepository;
import com.team1.reservation.reservation.repository.TicketDispatchRepository;
import com.team1.reservation.reservation.service.TicketDispatcher;
import com.team1.reservation.reservation.service.TicketIssueNotifier;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 통지 큐를 메모리로 대체한 조립기(#79).
 *
 * <p>Repository 를 그냥 Mock 으로 두면 save 한 행을 findById 가 못 찾아 즉시 시도가
 * 조용히 건너뛰어진다 - Test 는 통과하는데 통지는 안 나가는 최악의 조합이다.
 */
public final class TicketDispatchStub {

    // application.yml 의 값과 같게 유지한다. 발급과 무효화는 상한이 다르다(S7-6).
    public static final int MAX_ATTEMPTS = 6;
    public static final Duration BACKOFF = Duration.ofMinutes(1);
    public static final RetryPolicy ISSUE_POLICY =
            new RetryPolicy(MAX_ATTEMPTS, BACKOFF, Duration.ofMinutes(16));
    public static final RetryPolicy REVOKE_POLICY =
            new RetryPolicy(12, BACKOFF, Duration.ofHours(1));

    private TicketDispatchStub() {
    }

    public static TicketIssueNotifier notifier(TicketClient ticketClient, Clock clock) {
        TicketDispatchRepository queue = queue();
        return new TicketIssueNotifier(queue, dispatcher(queue, ticketClient, clock),
                new AfterCommitExecutor(), clock);
    }

    public static TicketDispatcher dispatcher(TicketDispatchRepository queue,
                                              TicketClient ticketClient, Clock clock) {
        return dispatcher(queue, confirmedReservations(), ticketClient, clock);
    }

    public static TicketDispatcher dispatcher(TicketDispatchRepository queue, ReservationRepository reservations,
                                              TicketClient ticketClient, Clock clock) {
        return new TicketDispatcher(queue, reservations, ticketClient, clock, ISSUE_POLICY, REVOKE_POLICY);
    }

    /** 발급 직전 상태 확인이 항상 CONFIRMED 를 보게 한다. 통지 자체를 보는 Test 들의 기본값이다. */
    public static ReservationRepository confirmedReservations() {
        ReservationRepository reservations = mock(ReservationRepository.class);
        Reservation confirmed = mock(Reservation.class);
        when(confirmed.getStatus()).thenReturn(ReservationStatus.CONFIRMED);
        when(reservations.findByIdForUpdate(any())).thenReturn(Optional.of(confirmed));
        return reservations;
    }

    /** id 를 붙여 저장하고 다시 찾아 주는 최소한의 가짜 Repository. */
    public static TicketDispatchRepository queue() {
        TicketDispatchRepository queue = mock(TicketDispatchRepository.class);
        Map<Long, TicketDispatch> store = new HashMap<>();
        AtomicLong sequence = new AtomicLong();

        when(queue.save(any())).thenAnswer(call -> {
            TicketDispatch dispatch = call.getArgument(0);
            if (dispatch.getId() == null) {
                ReflectionTestUtils.setField(dispatch, "id", sequence.incrementAndGet());
            }
            store.put(dispatch.getId(), dispatch);
            return dispatch;
        });
        when(queue.findById(any())).thenAnswer(call ->
                Optional.ofNullable(store.get(call.<Long>getArgument(0))));
        when(queue.findByReservationIdAndType(any(), any())).thenAnswer(call -> store.values().stream()
                .filter(d -> Objects.equals(d.getReservationId(), call.getArgument(0)))
                .filter(d -> d.getType() == call.getArgument(1))
                .findFirst());
        return queue;
    }
}
