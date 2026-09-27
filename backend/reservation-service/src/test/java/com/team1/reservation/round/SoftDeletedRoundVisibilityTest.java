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
import com.team1.reservation.reservation.service.MyReservationService;
import com.team1.reservation.reservation.service.ReservationCancelService;
import com.team1.reservation.reservation.service.ReservationPaymentService;
import com.team1.reservation.reservation.service.ReservationService;
import com.team1.reservation.round.dto.UpdateRoundRequest;
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
import static org.mockito.Mockito.when;

/**
 * S9-3 소프트 삭제가 읽는 지점에 제대로 반영되는지 본다.
 *
 * <p>판단 기준은 하나다 - <b>삭제된 회차가 "미래의 행동" 을 받는 경로는 필터하고,
 * "과거의 이력" 을 보여주는 경로는 필터하지 않는다.</b>
 * 하나라도 빠뜨리면 유령 회차가 보이거나 지난 예약이 사라진다.
 */
class SoftDeletedRoundVisibilityTest extends IntegrationTestSupport {

    private static final int CAPACITY = 10;
    private static final int FEE = 10000;
    private static final Long EXPO_ID = 1L;
    private static final long OWNER_ID = 99L;

    private static final AuthenticatedUser OWNER = new AuthenticatedUser(OWNER_ID, "ORGANIZER");
    private static final AuthenticatedUser MEMBER = new AuthenticatedUser(1L, "USER");

    @Autowired
    private RoundService roundService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationPaymentService paymentTransition;

    @Autowired
    private ReservationCancelService cancelService;

    @Autowired
    private MyReservationService myReservationService;

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
    private Long keptId;
    private Instant now;

    @BeforeEach
    void setUp() {
        reservations.deleteAll();
        rounds.deleteAll();

        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        roundId = futureRound();
        keptId = futureRound();   // 마지막 회차 자동 비공개를 타지 않게 하나 남겨 둔다

        when(expoClient.getExpo(anyLong())).thenReturn(new ExpoSummary(EXPO_ID, OWNER_ID, "PUBLISHED"));
        when(paymentService.createPending(any(), any())).thenAnswer(call ->
                PaymentTransaction.create(call.getArgument(0), "BE24-01-TEST",
                        call.getArgument(1), Instant.now()));
    }

    private Long futureRound() {
        return rounds.save(Round.create(EXPO_ID,
                now.plusSeconds(86400), now.plusSeconds(90000), CAPACITY, FEE, now)).getId();
    }

    // ---- 필터한다: 미래의 행동 ----

    @Test
    @DisplayName("삭제된 회차는 회차 목록에서 사라진다")
    void hiddenFromRoundList() {
        roundService.delete(EXPO_ID, roundId, OWNER);

        assertThat(roundService.listByExpo(EXPO_ID))
                .extracting(Round::getId)
                .containsExactly(keptId);
    }

    @Test
    @DisplayName("삭제된 회차에는 새 예약을 받지 않는다 (404)")
    void rejectsNewReservation() {
        roundService.delete(EXPO_ID, roundId, OWNER);

        assertThatThrownBy(() -> reservationService.create(roundId, MEMBER,
                new CreateReservationRequest(1, "홍길동", "01012345678")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("삭제된 회차를 수정하면 404 다 - 예약이 있다는 엉뚱한 오류(ROUND_HAS_RESERVATIONS)가 나가면 안 된다")
    void rejectsUpdateOfDeletedRound() {
        roundService.delete(EXPO_ID, roundId, OWNER);
        Instant startsAt = now.plus(10, ChronoUnit.DAYS);

        assertThatThrownBy(() -> roundService.update(EXPO_ID, roundId, OWNER,
                new UpdateRoundRequest(startsAt, startsAt.plus(2, ChronoUnit.HOURS), CAPACITY, FEE)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("삭제된 회차만 남은 박람회는 공개할 수 없다 - #24 의 판정에 걸린다")
    void cannotPublishWhenOnlyDeletedRoundsRemain() {
        roundService.delete(EXPO_ID, roundId, OWNER);
        roundService.delete(EXPO_ID, keptId, OWNER);

        assertThat(roundService.existsByExpo(EXPO_ID)).isFalse();
    }

    @Test
    @DisplayName("삭제된 회차는 자동 마감 판정에서 빠진다 - max(ends_at) 을 왜곡하면 안 된다")
    void excludedFromAutoCloseScan() {
        // 미래 회차 두 개를 모두 지우고, 이미 끝난 회차만 살려 둔다.
        roundService.delete(EXPO_ID, roundId, OWNER);
        roundService.delete(EXPO_ID, keptId, OWNER);
        rounds.save(Round.create(EXPO_ID, now.minusSeconds(7200), now.minusSeconds(3600),
                CAPACITY, FEE, now.minusSeconds(10800)));

        assertThat(roundService.finishedExpoIds(now, 100)).contains(EXPO_ID);
    }

    @Test
    @DisplayName("자동 마감 대상은 afterExpoId 커서 뒤의 박람회만 돌려준다 - 앞쪽 limit 개만 반복되면 안 된다")
    void finishedExpoIdsHonorsCursor() {
        roundService.delete(EXPO_ID, roundId, OWNER);
        roundService.delete(EXPO_ID, keptId, OWNER);
        rounds.save(Round.create(EXPO_ID, now.minusSeconds(7200), now.minusSeconds(3600),
                CAPACITY, FEE, now.minusSeconds(10800)));

        assertThat(roundService.finishedExpoIds(now, EXPO_ID - 1, 100)).contains(EXPO_ID);
        assertThat(roundService.finishedExpoIds(now, EXPO_ID, 100)).doesNotContain(EXPO_ID);
    }

    // ---- 필터하지 않는다: 과거의 이력 ----

    @Test
    @DisplayName("삭제된 회차의 지난 예약은 내 예약에 계속 보인다")
    void pastReservationSurvivesRoundDeletion() {
        Reservation reservation = reservationService.create(roundId, MEMBER,
                new CreateReservationRequest(2, "홍길동", "01012345678")).reservation();
        when(paymentService.confirm(reservation.getId()))
                .thenReturn(PaymentApprovalResult.success(reservation.getAmount()));
        paymentTransition.confirm(reservation.getId(), MEMBER);
        cancelService.cancel(reservation.getId(), MEMBER);

        roundService.delete(EXPO_ID, roundId, OWNER);

        assertThat(myReservationService.listMine(MEMBER))
                .extracting("reservationId")
                .contains(reservation.getId());
    }

    @Test
    @DisplayName("삭제된 회차의 예약 상세도 계속 열린다")
    void pastReservationDetailStillReadable() {
        Reservation reservation = reservationService.create(roundId, MEMBER,
                new CreateReservationRequest(1, "홍길동", "01012345678")).reservation();
        when(paymentService.confirm(reservation.getId()))
                .thenReturn(PaymentApprovalResult.success(reservation.getAmount()));
        paymentTransition.confirm(reservation.getId(), MEMBER);
        cancelService.cancel(reservation.getId(), MEMBER);

        roundService.delete(EXPO_ID, roundId, OWNER);

        assertThat(myReservationService.getMine(reservation.getId(), MEMBER)).isNotNull();
    }
}
