package com.team1.reservation.reservation.repository;

import com.team1.reservation.reservation.entity.Reservation;
import com.team1.reservation.reservation.entity.ReservationStatus;
import com.team1.reservation.reservation.dto.RoundStatusHeadcount;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /**
     * 티켓 발급 직전 상태 확인용. 발급하는 동안 취소(cancelIfActive)가 끼어들지 못하게 행을 잠근다 -
     * 끼어들면 무효화가 먼저 끝나고(티켓 없음) 발급이 뒤에 성공해 취소된 예약에 티켓이 남는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    /**
     * 같은 회차에 아직 살아 있는 예약이 있는지 본다.
     * 유효 상태(PENDING·CONFIRMED)만 골라 조회해야 재예약이 열린다.
     */
    boolean existsByRoundIdAndUserIdAndStatusIn(Long roundId, Long userId,
                                                Collection<ReservationStatus> statuses);

    List<Reservation> findByRoundIdAndStatusIn(Long roundId, Collection<ReservationStatus> statuses);

    /** 내 예약 목록(#82). 최신순이며 만료·취소된 것도 이력으로 보여준다. */
    List<Reservation> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 회차·상태별 예약 인원 합(#84 예약 현황). 정원은 rounds 에 있으므로 Service 가 합친다.
     */
    @Query("select new com.team1.reservation.reservation.dto.RoundStatusHeadcount("
            + "r.roundId, r.status, sum(r.headcount)) "
            + "from Reservation r where r.expoId = :expoId group by r.roundId, r.status")
    List<RoundStatusHeadcount> sumHeadcountByRoundAndStatus(@Param("expoId") Long expoId);


    /** 박람회 전체 명단(#84). 엑셀 다운로드는 회차를 나누지 않고 한 번에 받아간다. */
    List<Reservation> findByExpoIdAndStatusInOrderByRoundIdAscCreatedAtAsc(
            Long expoId, Collection<ReservationStatus> statuses);


    List<Reservation> findByExpoIdAndRoundIdAndStatusInOrderByCreatedAtAsc(
            Long expoId, Long roundId, Collection<ReservationStatus> statuses);


    /** 만료 후보(#77). 한 번에 다 긁지 않고 Pageable 로 끊어 배치가 길어지는 것을 막는다. */
    @Query("select r from Reservation r "
            + "where r.status = com.team1.reservation.reservation.entity.ReservationStatus.PENDING "
            + "and r.expiresAt <= :cutoff order by r.expiresAt asc")
    List<Reservation> findExpirable(@Param("cutoff") Instant cutoff, Pageable pageable);



     //PENDING 일 때만 EXPIRED 로 전이한다(#77).

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Reservation r "
            + "set r.status = com.team1.reservation.reservation.entity.ReservationStatus.EXPIRED "
            + "where r.id = :id "
            + "and r.status = com.team1.reservation.reservation.entity.ReservationStatus.PENDING")
    int expireIfPending(@Param("id") Long id);



     //아직 살아 있는 예약만 CANCELLED 로 전이한다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Reservation r "
            + "set r.status = com.team1.reservation.reservation.entity.ReservationStatus.CANCELLED, "
            + "r.cancelledAt = :now "
            + "where r.id = :id and r.status in ("
            + "com.team1.reservation.reservation.entity.ReservationStatus.PENDING, "
            + "com.team1.reservation.reservation.entity.ReservationStatus.CONFIRMED)")
    int cancelIfActive(@Param("id") Long id, @Param("now") Instant now);
}
