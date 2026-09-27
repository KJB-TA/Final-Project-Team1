package com.team1.ticket.ticket;

import com.team1.ticket.client.ExpoSummary;
import com.team1.ticket.support.IntegrationTestSupport;
import com.team1.ticket.ticket.entity.CheckinMethod;
import com.team1.ticket.ticket.entity.Ticket;
import com.team1.ticket.ticket.entity.TicketStatus;
import com.team1.ticket.ticket.repository.CheckinLogRepository;
import com.team1.ticket.ticket.repository.TicketRepository;
import com.team1.ticket.ticket.service.TicketCheckinService;
import com.team1.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// 스태프 여러 명이 같은 QR 을 거의 동시에 찍는 경우. 실제 DB 락으로 한 번만 입장되는지 본다.
class CheckinConcurrencyTest extends IntegrationTestSupport {

    private static final long EXPO_ID = 10L;
    private static final long OWNER_ID = 7L;
    private static final int STAFF = 5;

    private static final AuthenticatedUser OWNER = new AuthenticatedUser(OWNER_ID, "ORGANIZER");

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private CheckinLogRepository checkinLogs;

    @Autowired
    private TicketCheckinService checkinService;

    @BeforeEach
    void setUp() {
        checkinLogs.deleteAll();
        ticketRepository.deleteAll();
        when(expoClient.getExpo(EXPO_ID)).thenReturn(new ExpoSummary(EXPO_ID, OWNER_ID, "PUBLISHED", "테크 잡페어"));
    }

    @Test
    @DisplayName("같은 티켓을 동시에 체크인하면 한 건만 성공하고 이력도 1행만 남는다")
    void onlyOneConcurrentCheckinSucceeds() throws Exception {
        Ticket ticket = ticketRepository.saveAndFlush(
                Ticket.issue(123L, "R-4K7Q-W2M8", EXPO_ID, 45L, 77L, 2, "tok-1", Instant.now().minusSeconds(3600)));

        ExecutorService pool = Executors.newFixedThreadPool(STAFF);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < STAFF; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    checkinService.checkin(ticket.getId(), CheckinMethod.QR, OWNER);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                succeeded++;
            }
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(ticketRepository.findById(ticket.getId()).orElseThrow().getStatus()).isEqualTo(TicketStatus.USED);
        assertThat(checkinLogs.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).hasSize(1);
    }
}
