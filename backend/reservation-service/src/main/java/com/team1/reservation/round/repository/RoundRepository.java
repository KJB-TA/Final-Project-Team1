package com.team1.reservation.round.repository;

import com.team1.reservation.round.dto.NearestDeadlineView;
import com.team1.reservation.round.entity.Round;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface RoundRepository extends JpaRepository<Round, Long> {

    // 삭제된 회차는 "미래의 행동" 을 받는 경로에서 전부 빠진다(S9-3).
    List<Round> findByExpoIdAndDeletedAtIsNullOrderByStartsAtAsc(Long expoId);

    /**
     * 목록 배지용 - 예약 가능한 회차가 하나라도 남은 박람회 id.
     * 삭제됐거나 이미 시작한 회차는 예약을 받지 못하므로 배지 판정에서도 빠진다.
     */
    @Query("select distinct r.expoId from Round r "
            + "where r.expoId in :expoIds and r.deletedAt is null and r.startsAt > :now")
    List<Long> findExpoIdsWithOpenRounds(@Param("expoIds") Collection<Long> expoIds, @Param("now") Instant now);


    // 위와 같은 조건에 fee > 0 만 더한다. 한 회차라도 걸리면 그 박람회는 유료다.
    @Query("select distinct r.expoId from Round r "
            + "where r.expoId in :expoIds and r.deletedAt is null and r.startsAt > :now and r.fee > 0")
    List<Long> findExpoIdsWithPaidOpenRounds(@Param("expoIds") Collection<Long> expoIds, @Param("now") Instant now);


    // 내 예약 화면이 회차 번호를 매기려면 그 박람회의 살아있는 회차를 전부 알아야 한다.
    List<Round> findByExpoIdInAndDeletedAtIsNull(Collection<Long> expoIds);

    /**
     * 캘린더·자연어 검색의 날짜 범위 조회(계약 2 roundsByDate). 기간 겹침으로 판정한다 -
     * DATE(starts_at) 비교는 이틀 걸치는 회차를 놓친다. bookableOnly 는 예약 가능한(시작 전) 회차만 거른다.
     */
    @Query("select r from Round r where r.expoId in :expoIds and r.deletedAt is null "
            + "and r.startsAt < :to and r.endsAt > :from "
            + "and (:bookableOnly = false or r.startsAt > :now)")
    List<Round> findByExpoIdInAndDateRange(@Param("expoIds") Collection<Long> expoIds,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to,
                                           @Param("bookableOnly") boolean bookableOnly,
                                           @Param("now") Instant now);


    /**
     * 이 회차보다 앞선 회차 수. +1 이 곧 회차 번호다(단건 조회용).
     * 조건이 {@code RoundSequence.ORDER} 와 정확히 같아야 목록과 단건의 번호가 어긋나지 않는다.
     */
    @Query("select count(r) from Round r where r.expoId = :expoId and r.deletedAt is null "
            + "and (r.startsAt < :startsAt or (r.startsAt = :startsAt and r.id < :roundId))")
    long countEarlierRounds(@Param("expoId") Long expoId,
                            @Param("startsAt") Instant startsAt,
                            @Param("roundId") Long roundId);


    // 박람회 공개 조건(#24). 삭제된 회차만 있는 박람회가 공개되면 안 된다.
    boolean existsByExpoIdAndDeletedAtIsNull(Long expoId);

    // 마지막 살아있는 회차인지 판정한다. 1 이면 지금 지우는 그 회차가 마지막이다.
    long countByExpoIdAndDeletedAtIsNull(Long expoId);


    // 삭제된 회차가 max(ends_at) 판정을 왜곡하면 안 된다.
    // 이미 CLOSED 인 박람회도 계속 나오므로(이 Schema 는 박람회 상태를 모른다) afterExpoId 커서로 넘겨 받는다.
    // 커서가 없으면 앞쪽 limit 개만 계속 돌아와 그 뒤 박람회는 영영 마감되지 않는다.
    @Query("select r.expoId from Round r where r.deletedAt is null and r.expoId > :afterExpoId "
            + "group by r.expoId having max(r.endsAt) < :before order by r.expoId")
    List<Long> findExpoIdsWithAllRoundsEndedBefore(@Param("before") Instant before,
                                                   @Param("afterExpoId") long afterExpoId,
                                                   Pageable pageable);

    /** 박람회별 가장 가까운 모집 마감일(endsAt). now 이후 회차만 집계한다. */
    @Query("select new com.team1.reservation.round.dto.NearestDeadlineView(r.expoId, min(r.endsAt)) "
            + "from Round r "
            + "where r.expoId in :expoIds and r.deletedAt is null and r.endsAt > :now "
            + "group by r.expoId")
    List<NearestDeadlineView> findNearestDeadlinesByExpoIds(@Param("expoIds") Collection<Long> expoIds,
                                                            @Param("now") Instant now);



    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Round r set r.reservedCount = r.reservedCount + :headcount "
            + "where r.id = :roundId and r.deletedAt is null "
            + "and r.reservedCount + :headcount <= r.capacity")
    int reserve(@Param("roundId") Long roundId, @Param("headcount") int headcount);



    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Round r set r.reservedCount = r.reservedCount - :headcount "
            + "where r.id = :roundId and r.reservedCount - :headcount >= 0")
    int release(@Param("roundId") Long roundId, @Param("headcount") int headcount);


    /**
     * 활성 예약이 없을 때만 수정한다(S9-2). 읽어서 판단하면 그 사이에 들어온 예약을 놓치므로
     * 조건을 UPDATE 문 안에 둔다 - 예약 생성의 정원 차감(reserve)과 같은 모양이다.
     *
     * <p>reserved_count 는 reserve() 가 올리고 release() 가 내리므로 정확히 활성 예약 인원이다.
     * 예약이 모두 취소되면 0 으로 돌아와 자동으로 다시 수정 가능해진다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Round r set r.startsAt = :startsAt, r.endsAt = :endsAt, "
            + "r.capacity = :capacity, r.fee = :fee "
            + "where r.id = :roundId and r.reservedCount = 0 and r.deletedAt is null")
    int updateIfNoReservation(@Param("roundId") Long roundId,
                              @Param("startsAt") Instant startsAt,
                              @Param("endsAt") Instant endsAt,
                              @Param("capacity") int capacity,
                              @Param("fee") int fee);


    /**
     * 활성 예약이 없을 때만 삭제한다(S9-3). 수정과 같은 이유로 조건을 UPDATE 안에 둔다.
     * 이미 삭제된 회차는 0 행이 되어 호출부가 멱등으로 처리한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Round r set r.deletedAt = :now "
            + "where r.id = :roundId and r.reservedCount = 0 and r.deletedAt is null")
    int softDeleteIfNoReservation(@Param("roundId") Long roundId, @Param("now") Instant now);
}
