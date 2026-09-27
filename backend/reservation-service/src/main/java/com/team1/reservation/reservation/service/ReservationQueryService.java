package com.team1.reservation.reservation.service;

import com.team1.reservation.reservation.dto.AttendeeResponse;
import com.team1.reservation.reservation.dto.InternalReservationPaymentResponse;
import com.team1.reservation.reservation.dto.ReservationSummaryResponse;
import com.team1.reservation.reservation.dto.RoundStatusHeadcount;
import com.team1.reservation.reservation.entity.ReservationStatus;
import com.team1.reservation.reservation.repository.PaymentLookupRepository;
import com.team1.reservation.reservation.repository.ReservationRepository;
import com.team1.reservation.round.entity.Round;
import com.team1.reservation.round.repository.RoundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class ReservationQueryService {


    static final Set<ReservationStatus> LISTED = EnumSet.of(
            ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED);

    private final ReservationRepository reservations;
    private final RoundRepository rounds;
    private final PaymentLookupRepository payments;

    public ReservationQueryService(ReservationRepository reservations, RoundRepository rounds, PaymentLookupRepository payments) {
        this.reservations = reservations;
        this.rounds = rounds;
        this.payments = payments;
    }


     //* 회차별 예약 현황. 예약이 하나도 없는 회차도 0 으로 채워 반환한다 —
    @Transactional(readOnly = true)
    public List<ReservationSummaryResponse> summary(Long expoId) {
        Map<Long, Map<ReservationStatus, Long>> counts = new HashMap<>();
        for (RoundStatusHeadcount row : reservations.sumHeadcountByRoundAndStatus(expoId)) {
            counts.computeIfAbsent(row.roundId(), k -> new EnumMap<>(ReservationStatus.class))
                    .put(row.status(), row.headcount());
        }

        List<Round> roundsOfExpo = rounds.findByExpoIdAndDeletedAtIsNullOrderByStartsAtAsc(expoId);

        return roundsOfExpo.stream()
                .map(round -> {
                    Map<ReservationStatus, Long> byStatus =
                            counts.getOrDefault(round.getId(), Map.of());
                    return new ReservationSummaryResponse(
                            round.getId(),
                            round.getCapacity(),
                            headcount(byStatus, ReservationStatus.CONFIRMED),
                            headcount(byStatus, ReservationStatus.CANCELLED),
                            round.getStartsAt(),
                            round.getEndsAt());
                })
                .toList();
    }

    //엑셀 다운로드는 회차를 나누지 않고 한 번에 받아간다.
    @Transactional(readOnly = true)
    public List<AttendeeResponse> attendees(Long expoId, Long roundId) {
        List<com.team1.reservation.reservation.entity.Reservation> found = (roundId == null)
                ? reservations.findByExpoIdAndStatusInOrderByRoundIdAscCreatedAtAsc(expoId, LISTED)
                : reservations.findByExpoIdAndRoundIdAndStatusInOrderByCreatedAtAsc(expoId, roundId, LISTED);

        return found.stream().map(AttendeeResponse::from).toList();
    }

    /** 정산의 박람회별 집계는 결제 트랜잭션엔 없는 expoId 가 필요해, 예약을 일괄 조회해 붙인다. */
    @Transactional(readOnly = true)
    public List<InternalReservationPaymentResponse> getPaymentsForSettlement(Instant from, Instant to) {
        List<com.team1.payment.PaymentTransaction> found = payments.findSettlementEvents(from, to);

        List<Long> reservationIds = found.stream()
                .map(com.team1.payment.PaymentTransaction::getRefId)
                .distinct()
                .toList();
        Map<Long, Long> expoIdByReservationId = reservations.findAllById(reservationIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.team1.reservation.reservation.entity.Reservation::getId,
                        com.team1.reservation.reservation.entity.Reservation::getExpoId));

        return found.stream()
                .map(tx -> InternalReservationPaymentResponse.of(tx, expoIdByReservationId.get(tx.getRefId())))
                .toList();
    }

    private int headcount(Map<ReservationStatus, Long> byStatus, ReservationStatus status) {
        return Math.toIntExact(byStatus.getOrDefault(status, 0L));
    }
}
