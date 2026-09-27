package com.team1.ticket.ticket;

import com.team1.ticket.client.ExpoSummary;
import com.team1.ticket.support.IntegrationTestSupport;
import com.team1.ticket.ticket.entity.CheckinAction;
import com.team1.ticket.ticket.entity.CheckinLog;
import com.team1.ticket.ticket.entity.CheckinMethod;
import com.team1.ticket.ticket.entity.Ticket;
import com.team1.ticket.ticket.repository.CheckinLogRepository;
import com.team1.ticket.ticket.repository.TicketRepository;
import com.team1.ticket.ticket.service.TicketCheckinService;
import com.team1.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// S7-5 체크인 이력. 실제 DB 로 매핑과 REQUIRES_NEW 커밋을 확인한다.
class CheckinLogTest extends IntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-09-20T05:00:00Z");
    private static final long EXPO_ID = 10L;
    private static final long OWNER_ID = 7L;

    private static final AuthenticatedUser OWNER = new AuthenticatedUser(OWNER_ID, "ORGANIZER");

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private CheckinLogRepository checkinLogs;

    @Autowired
    private TicketCheckinService checkinService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        checkinLogs.deleteAll();
        ticketRepository.deleteAll();
        when(expoClient.getExpo(EXPO_ID)).thenReturn(new ExpoSummary(EXPO_ID, OWNER_ID, "PUBLISHED", "테크 잡페어"));
    }

    private Ticket issued() {
        return ticketRepository.saveAndFlush(
                Ticket.issue(123L, "R-4K7Q-W2M8", EXPO_ID, 45L, 77L, 2, "tok-1", NOW.minusSeconds(3600)));
    }

    @Test
    @DisplayName("체크인하면 누가 무엇으로 처리했는지 1행이 남는다")
    void recordsWhoCheckedIn() {
        Ticket ticket = issued();

        checkinService.checkin(ticket.getId(), CheckinMethod.RESERVATION_NO, OWNER);

        List<CheckinLog> logs = checkinLogs.findByTicketIdOrderByCreatedAtAsc(ticket.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAction()).isEqualTo(CheckinAction.CHECK_IN);
        assertThat(logs.get(0).getActorUserId()).isEqualTo(OWNER_ID);
        assertThat(logs.get(0).getMethod()).isEqualTo(CheckinMethod.RESERVATION_NO);
        assertThat(logs.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("체크인이 롤백되면 이력도 남지 않는다 - 이력은 커밋된 뒤에만 쓴다")
    void noLogWhenCheckinRollsBack() {
        Ticket ticket = issued();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            checkinService.checkin(ticket.getId(), CheckinMethod.QR, OWNER);
            status.setRollbackOnly();
        });

        assertThat(checkinLogs.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).isEmpty();
    }

    @Test
    @DisplayName("화면이 수단을 안 보내면 method 는 NULL 로 남고 기록은 그대로 된다")
    void recordsWithoutMethod() {
        Ticket ticket = issued();

        checkinService.checkin(ticket.getId(), null, OWNER);

        List<CheckinLog> logs = checkinLogs.findByTicketIdOrderByCreatedAtAsc(ticket.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getMethod()).isNull();
    }
}
