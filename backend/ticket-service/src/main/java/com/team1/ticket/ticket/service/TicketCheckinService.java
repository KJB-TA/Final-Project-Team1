package com.team1.ticket.ticket.service;

import com.team1.ticket.client.ExpoClient;
import com.team1.ticket.client.ExpoSummary;
import com.team1.ticket.client.RecommendationClient;
import com.team1.ticket.client.RoundClient;
import com.team1.ticket.client.RoundInfo;
import com.team1.ticket.common.ApiException;
import com.team1.ticket.common.ErrorCode;
import com.team1.ticket.common.TraceId;
import com.team1.ticket.ticket.dto.CheckinResult;
import com.team1.ticket.ticket.dto.CheckinTicketView;
import com.team1.ticket.ticket.entity.CheckinAction;
import com.team1.ticket.ticket.entity.CheckinMethod;
import com.team1.ticket.ticket.entity.Ticket;
import com.team1.ticket.ticket.entity.TicketStatus;
import com.team1.ticket.ticket.repository.TicketRepository;
import com.team1.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;


// 현장 체크인(Story 7, #74). 주최자가 브라우저에서 호출하는 외부 API 를 서빙한다.
// 흐름: verify(스캔→조회, 미전이) → checkin(확정→USED). 둘 다 박람회 소유권을 검증한다.
// 조회 수단은 QR 의 체크인 토큰과 예약번호 두 가지지만, 확정은 checkin() 하나로 모인다.
@Service
public class TicketCheckinService {

    private static final Logger log = LoggerFactory.getLogger(TicketCheckinService.class);
    private static final String ROLE_ORGANIZER = "ORGANIZER";

    private final TicketRepository ticketRepository;
    private final ExpoClient expoClient;
    private final RoundClient roundClient;
    private final CheckinLogWriter checkinLogWriter;
    private final RecommendationClient recommendationClient;
    private final Clock clock;
    private final Duration opensBefore;

    public TicketCheckinService(TicketRepository ticketRepository, ExpoClient expoClient,
                                RoundClient roundClient, CheckinLogWriter checkinLogWriter,
                                RecommendationClient recommendationClient,
                                Clock clock,
                                @Value("${checkin.opens-before}") Duration opensBefore) {
        this.ticketRepository = ticketRepository;
        this.expoClient = expoClient;
        this.roundClient = roundClient;
        this.checkinLogWriter = checkinLogWriter;
        this.recommendationClient = recommendationClient;
        this.clock = clock;
        this.opensBefore = opensBefore;
    }

    // 체크인 토큰 또는 예약번호로 티켓을 조회한다. 상태를 바꾸지 않는다(주최자가 확정 전에 확인).
    @Transactional(readOnly = true)
    public CheckinTicketView verify(String code, String reservationNo, AuthenticatedUser organizer) {
        requireOrganizer(organizer);
        Ticket ticket = findTicket(code, reservationNo);
        ExpoSummary expo = verifyOwnership(ticket, organizer);
        // 회차 번호는 보여주기용이라 못 받아와도 조회를 막지 않는다 - 시간창과 같은 fail-open 이다.
        RoundInfo round = roundClient.findRound(ticket.getRoundId());
        return CheckinTicketView.from(ticket,
                expo.title(),
                round == null ? null : round.sequence());
    }

    // 체크인 확정. ISSUED → USED (1회용). 이미 사용/취소면 거부.
    @Transactional
    public CheckinResult checkin(Long ticketId, CheckinMethod method, AuthenticatedUser organizer) {
        requireOrganizer(organizer);
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "ticket not found: " + ticketId));
        verifyOwnership(ticket, organizer);
        Instant now = clock.instant();
        requireWithinCheckinWindow(ticket, now);
        ticket.checkIn(now);
        afterCommit(() -> {
            recordQuietly(ticket.getId(), CheckinAction.CHECK_IN, organizer.userId(), method, now);
            recommendationClient.sendCheckinEvent(ticket.getUserId(), ticket.getExpoId());
        });
        return CheckinResult.from(ticket);
    }

    // 체크인 되돌리기. USED → ISSUED. 시간창은 보지 않는다 - 창이 닫힌 뒤에도 오처리는 복구돼야 한다.
    @Transactional
    public CheckinResult cancelCheckin(Long ticketId, AuthenticatedUser organizer) {
        requireOrganizer(organizer);
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "ticket not found: " + ticketId));
        verifyOwnership(ticket, organizer);

        boolean wasCheckedIn = ticket.getStatus() == TicketStatus.USED;
        Instant now = clock.instant();
        ticket.cancelCheckIn();
        // 실제로 되돌린 경우에만 남긴다. 두 번 눌렀다고 이력이 두 줄이면 이력이 거짓말을 한다.
        if (wasCheckedIn) {
            afterCommit(() -> recordQuietly(ticket.getId(), CheckinAction.CANCEL, organizer.userId(), null, now));
        }
        return CheckinResult.from(ticket);
    }

    // 이력(REQUIRES_NEW)과 추천 이벤트는 체크인이 커밋된 뒤에만 남긴다. 먼저 남기면 체크인이
    // 롤백돼도 이력과 이벤트는 그대로 남는다(예약 쪽 AfterCommitExecutor 와 같은 방식).
    private void afterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    // 이력은 부가 기능이다. 기록이 실패해도 체크인은 그대로 둔다.
    private void recordQuietly(Long ticketId, CheckinAction action, Long actorUserId,
                               CheckinMethod method, Instant now) {
        try {
            checkinLogWriter.record(ticketId, action, actorUserId, method, now);
        } catch (Exception e) {
            log.warn("checkin log not written ticketId={} action={} traceId={}",
                    ticketId, action, TraceId.get(), e);
        }
    }

    private Ticket findTicket(String code, String reservationNo) {
        boolean hasCode = hasText(code);
        boolean hasReservationNo = hasText(reservationNo);
        // 둘 다 받으면 어느 쪽을 믿을지가 애매해지고, 서로 다른 티켓을 가리킬 수도 있다.
        if (hasCode == hasReservationNo) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "exactly one of code or reservationNo is required");
        }
        Optional<Ticket> found = hasCode
                ? ticketRepository.findByCheckinToken(code.trim())
                : ticketRepository.findByReservationNo(reservationNo.trim().toUpperCase(Locale.ROOT));
        // 예약번호의 존재 여부를 흘리지 않으려고 토큰 경로와 같은 메시지를 쓴다.
        return found.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "invalid ticket"));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // 회차 시각은 티켓에 저장하지 않고 매번 조회한다. Story 9 로 일정을 옮기면 저장된 값이 낡는다.
    // 조회 실패는 fail-open - 부가 검증 하나 때문에 현장 입장 줄을 멈추지 않는다.
    private void requireWithinCheckinWindow(Ticket ticket, Instant now) {
        RoundInfo round = roundClient.findRound(ticket.getRoundId());
        if (round == null || round.startsAt() == null || round.endsAt() == null) {
            log.warn("checkin window not verified roundId={} traceId={}", ticket.getRoundId(), TraceId.get());
            return;
        }
        // 메시지에 ISO 시각을 담아 화면이 "언제부터 가능한지" 를 보여줄 수 있게 한다.
        Instant opensAt = round.startsAt().minus(opensBefore);
        if (now.isBefore(opensAt)) {
            throw new ApiException(ErrorCode.CHECKIN_NOT_OPEN, "checkin opens at " + opensAt);
        }
        if (now.isAfter(round.endsAt())) {
            throw new ApiException(ErrorCode.CHECKIN_CLOSED, "checkin closed at " + round.endsAt());
        }
    }

    // 역할은 티켓을 찾기 전에 본다. 뒤에서 보면 회원이 404/403 차이로 QR·예약번호 존재 여부를 알아낸다.
    private void requireOrganizer(AuthenticatedUser organizer) {
        if (organizer == null || !ROLE_ORGANIZER.equals(organizer.role())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "organizer role required");
        }
    }

    // 주최자만, 그리고 그 티켓 박람회의 소유자만 체크인할 수 있다.
    // 소유권은 박람회-Service 만 알고 있어 getExpoInternal 로 확인한다(#7). 실패는 fail-closed.
    /** 조회한 박람회를 그대로 돌려준다 - 체크인 화면이 제목을 쓰려고 다시 묻지 않게 한다. */
    private ExpoSummary verifyOwnership(Ticket ticket, AuthenticatedUser organizer) {
        requireOrganizer(organizer);
        ExpoSummary expo = expoClient.getExpo(ticket.getExpoId());
        if (!expo.channelOwnerId().equals(organizer.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "not the owner of this expo");
        }
        return expo;
    }
}
