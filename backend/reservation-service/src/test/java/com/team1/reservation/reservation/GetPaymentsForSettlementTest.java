package com.team1.reservation.reservation;

import com.team1.payment.PaymentTransaction;
import com.team1.reservation.reservation.dto.InternalReservationPaymentResponse;
import com.team1.reservation.reservation.entity.Reservation;
import com.team1.reservation.reservation.repository.PaymentLookupRepository;
import com.team1.reservation.reservation.repository.ReservationRepository;
import com.team1.reservation.reservation.service.ReservationQueryService;
import com.team1.reservation.round.repository.RoundRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 정산(Settlement-Service)이 박람회별 매출 랭킹을 내려면 결제마다 expoId 가 있어야 하는데,
 * 결제 트랜잭션 자체엔 없다(예약을 거쳐야 안다). 그 역참조 조인이 맞는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class GetPaymentsForSettlementTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    @Mock
    private ReservationRepository reservations;
    @Mock
    private RoundRepository rounds;
    @Mock
    private PaymentLookupRepository payments;

    private ReservationQueryService service() {
        return new ReservationQueryService(reservations, rounds, payments);
    }

    private Reservation reservationWithId(long id, long expoId) {
        Reservation r = Reservation.create("BE24-01", 1L, expoId, 1L, "홍길동", "01000000000", 1, 10000, NOW);
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    private PaymentTransaction paidTx(long refId) {
        PaymentTransaction tx = PaymentTransaction.create(refId, "PAY-" + refId, 10000, NOW);
        tx.markPaid("pg-tx", "0000", NOW);
        return tx;
    }

    @Test
    @DisplayName("결제의 refId(예약 id)로 예약을 찾아 expoId를 붙인다")
    void attachesExpoIdFromReservation() {
        when(payments.findSettlementEvents(any(), any()))
                .thenReturn(List.of(paidTx(42L)));
        when(reservations.findAllById(List.of(42L))).thenReturn(List.of(reservationWithId(42L, 7L)));

        List<InternalReservationPaymentResponse> result =
                service().getPaymentsForSettlement(NOW, NOW.plusSeconds(3600));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).reservationId()).isEqualTo(42L);
        assertThat(result.get(0).expoId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("예약이 이미 지워졌거나 못 찾아도 결제 자체는 빠뜨리지 않는다 - expoId만 null")
    void missingReservationLeavesExpoIdNull() {
        when(payments.findSettlementEvents(any(), any()))
                .thenReturn(List.of(paidTx(99L)));
        when(reservations.findAllById(List.of(99L))).thenReturn(List.of());

        List<InternalReservationPaymentResponse> result =
                service().getPaymentsForSettlement(NOW, NOW.plusSeconds(3600));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).expoId()).isNull();
    }
}
