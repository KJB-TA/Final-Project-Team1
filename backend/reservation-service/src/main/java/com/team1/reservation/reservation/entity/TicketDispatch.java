package com.team1.reservation.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/**
 * 티켓 발급 통지 1건(#79). 예약 확정과 같은 Transaction 에 적재되고, 성공할 때까지 배치가 다시 집는다.
 */
@Entity
@Table(name = "ticket_dispatch_queue")
@Getter
public class TicketDispatch {

    /** 오류 메시지가 컬럼 길이를 넘으면 INSERT 가 통째로 실패한다. 통지 기록을 잃느니 자른다. */
    private static final int MAX_ERROR_LENGTH = 500;


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @Column(nullable = false, updatable = false)
    private Long reservationId;


    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private TicketDispatchType type;

    // DB 는 NULL 을 허용한다(이미 쌓인 행에 채울 값이 없다). 신규 적재는 팩터리가 강제한다.
    @Column(updatable = false, length = 20)
    private String reservationNo;

    @Column(nullable = false, updatable = false)
    private Long expoId;


    @Column(nullable = false, updatable = false)
    private Long roundId;


    @Column(nullable = false, updatable = false)
    private Long userId;


    @Column(nullable = false, updatable = false)
    private int headcount;


    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketDispatchStatus status;


    @Column(nullable = false)
    private int attempts;


    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private Long ticketId;


    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TicketDispatch() {
    }

    private TicketDispatch(Long reservationId, String reservationNo, TicketDispatchType type,
                           Long expoId, Long roundId, Long userId, int headcount, Instant now) {
        this.reservationId = reservationId;
        this.reservationNo = reservationNo;
        this.type = type;
        this.expoId = expoId;
        this.roundId = roundId;
        this.userId = userId;
        this.headcount = headcount;
        this.status = TicketDispatchStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static TicketDispatch issue(Long reservationId, String reservationNo, Long expoId,
                                       Long roundId, Long userId, int headcount, Instant now) {
        return new TicketDispatch(reservationId, reservationNo, TicketDispatchType.ISSUE,
                expoId, roundId, userId, headcount, now);
    }

    public static TicketDispatch revoke(Long reservationId, String reservationNo, Long expoId,
                                        Long roundId, Long userId, int headcount, Instant now) {
        return new TicketDispatch(reservationId, reservationNo, TicketDispatchType.REVOKE,
                expoId, roundId, userId, headcount, now);
    }

    public void succeeded(Long ticketId, Instant now) {
        this.status = TicketDispatchStatus.SUCCEEDED;
        this.ticketId = ticketId;
        this.lastError = null;
        this.updatedAt = now;
    }


    // 더 보낼 이유가 없어진 통지(취소된 예약의 발급 등). 재시도하지 않도록 바로 끝낸다.
    public void abandon(String reason, Instant now) {
        this.status = TicketDispatchStatus.GAVE_UP;
        this.lastError = truncate(reason);
        this.updatedAt = now;
    }

    // 시도 실패를 기록하고 다음 시도 시각을 미룬다. 상한·간격은 type 별 정책이 정한다.
    public void failed(String reason, RetryPolicy policy, Instant now) {
        this.attempts++;
        this.lastError = truncate(reason);
        this.updatedAt = now;

        if (attempts >= policy.maxAttempts()) {
            this.status = TicketDispatchStatus.GAVE_UP;
            return;
        }
        this.nextAttemptAt = now.plus(policy.delayFor(attempts));
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_ERROR_LENGTH ? reason : reason.substring(0, MAX_ERROR_LENGTH);
    }

    public boolean isPending() {
        return status == TicketDispatchStatus.PENDING;
    }

}
