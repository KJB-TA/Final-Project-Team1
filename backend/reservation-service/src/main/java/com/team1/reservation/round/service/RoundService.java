package com.team1.reservation.round.service;

import com.team1.reservation.client.ExpoClient;
import com.team1.reservation.client.ExpoSummary;
import com.team1.reservation.common.ApiException;
import com.team1.reservation.common.ErrorCode;
import com.team1.reservation.round.dto.CreateRoundRequest;
import com.team1.reservation.round.dto.DeadlineSortResult;
import com.team1.reservation.round.dto.ExpoFeeSummaryResponse;
import com.team1.reservation.round.dto.InternalRoundResponse;
import com.team1.reservation.round.dto.NearestDeadlineView;
import com.team1.reservation.round.dto.UpdateRoundRequest;
import com.team1.reservation.round.entity.Round;
import com.team1.reservation.round.repository.RoundRepository;
import com.team1.security.AuthenticatedUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RoundService {


    static final String ROLE_ORGANIZER = "ORGANIZER";

    /**
     * 자동 비공개 대상 판정에 쓴다. expo-Service 의 ExpoStatus.PUBLISHED 와 같은 문자열이다.
     */
    private static final String EXPO_PUBLISHED = "PUBLISHED";


    public static final int MAX_FINISHED_EXPO_LIMIT = 1000;

    /**
     * 목록 한 페이지(최대 100)를 넘는 요청은 내부 호출부의 실수로 본다.
     */
    
    static final int MAX_FEE_SUMMARY_IDS = 200;

    /**
     * 캘린더 검색이 한번에 확인 할 수 있는 최대기간. 이보다 넓으면 후보가 과도하게 많아짐.
     */
    static final Duration MAX_DATE_RANGE = Duration.ofDays(90);
    
    

    private final RoundRepository rounds;
    private final ExpoClient expoClient;
    private final Clock clock;

    public RoundService(RoundRepository rounds, ExpoClient expoClient, Clock clock) {
        this.rounds = rounds;
        this.expoClient = expoClient;
        this.clock = clock;
    }

    @Transactional
    public Round create(Long expoId, AuthenticatedUser user, CreateRoundRequest request) {

        Round round = Round.create(
                expoId,
                request.startsAt(),
                request.endsAt(),
                request.capacity(),
                request.feeOrZero(),
                clock.instant());

        requireOwnership(expoId, user);

        return rounds.save(round);
    }

    @Transactional(readOnly = true)
    public List<Round> listForOrganizer(Long expoId, AuthenticatedUser user) {
        requireOwnership(expoId, user);

        return RoundSequence.ordered(rounds.findByExpoIdAndDeletedAtIsNullOrderByStartsAtAsc(expoId));
    }

    /**
     * 회차 일정·정원·참가비 수정(S9-2). 활성 예약이 한 건이라도 있으면 거절한다.
     *
     * <p>참가비를 바꿔도 이미 만들어진 예약의 결제 금액은 변하지 않는다 -
     * reservations.amount 는 신청 시점에 고정된 값이다. 애초에 활성 예약이 0건일 때만
     * 수정되므로 영향받을 예약도 없다.
     */
    @Transactional
    public Round update(Long expoId, Long roundId, AuthenticatedUser user, UpdateRoundRequest request) {
        requireOwnership(expoId, user);

        Round round = rounds.findById(roundId)
                .filter(r -> Objects.equals(r.getExpoId(), expoId))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "round not found: " + roundId));

        Instant now = clock.instant();
        // 이미 시작한 회차는 손대지 않는다. 입장이 진행 중인데 시각·정원이 바뀌면 현장과 어긋난다.
        if (!round.getStartsAt().isAfter(now)) {
            throw new ApiException(ErrorCode.ROUND_ALREADY_STARTED,
                    "round has already started: " + roundId);
        }

        Round.validate(request.startsAt(), request.endsAt(), request.capacity(), request.fee(), now);

        // 읽고 판단하면 그 사이에 들어온 예약을 놓친다. 조건을 UPDATE 안에 둔다.
        int updated = rounds.updateIfNoReservation(roundId, request.startsAt(), request.endsAt(),
                request.capacity(), request.fee());
        if (updated == 0) {
            throw new ApiException(ErrorCode.ROUND_HAS_RESERVATIONS,
                    "round has active reservations: " + roundId);
        }

        // clearAutomatically 로 영속성 컨텍스트가 비워졌다. 갱신된 값을 다시 읽는다.
        return rounds.findById(roundId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "round not found: " + roundId));
    }

    /**
     * 회차 삭제(S9-3). 소프트 삭제이며 활성 예약이 0건이고 아직 시작하지 않은 회차만 지운다.
     *
     * <p>되돌릴 수 있는 로컬 삭제를 먼저, 되돌릴 수 없는 원격 비공개를 나중에 한다.
     * 비공개 호출이 실패하면 예외로 트랜잭션이 롤백돼 삭제도 없던 일이 된다.
     */
    @Transactional
    public void delete(Long expoId, Long roundId, AuthenticatedUser user) {
        ExpoSummary expo = requireOwnership(expoId, user);

        Round round = rounds.findById(roundId)
                .filter(r -> Objects.equals(r.getExpoId(), expoId))
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "round not found: " + roundId));

        Instant now = clock.instant();
        if (!round.getStartsAt().isAfter(now)) {
            throw new ApiException(ErrorCode.ROUND_ALREADY_STARTED,
                    "round has already started: " + roundId);
        }

        // 조건부 UPDATE 가 행 잠금을 잡으므로 커밋 전까지 이 회차에 새 예약이 끼어들지 못한다.
        if (rounds.softDeleteIfNoReservation(roundId, now) == 0) {
            throw new ApiException(ErrorCode.ROUND_HAS_RESERVATIONS,
                    "round has active reservations: " + roundId);
        }

        // 방금 지운 것이 마지막 회차이고 공개 중이면 비공개로 바꾼다. 실패하면 위 삭제까지 롤백된다.
        if (EXPO_PUBLISHED.equals(expo.status())
                && rounds.countByExpoIdAndDeletedAtIsNull(expoId) == 0) {
            expoClient.unpublish(expoId);
        }
    }

    // Ticket-Service 의 체크인 시간창 검증이 쓴다(계약 2 getRoundInternal).
    // 삭제 여부로 거르지 않는다 - 이미 발급된 티켓의 회차 시각을 확인하는 용도다.
    @Transactional(readOnly = true)
    public Round getById(Long roundId) {
        return rounds.findById(roundId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "round not found: " + roundId));
    }

    @Transactional(readOnly = true)
    public List<Round> listByExpo(Long expoId) {
        return RoundSequence.ordered(rounds.findByExpoIdAndDeletedAtIsNullOrderByStartsAtAsc(expoId));
    }

    /**
     * 단건의 회차 번호. 목록이 없을 때만 쓴다 - 목록은 정렬 순서로 매기는 편이 쿼리 없이 끝난다.
     */
    @Transactional(readOnly = true)
    public int sequenceOf(Round round) {
        return (int) rounds.countEarlierRounds(
                round.getExpoId(), round.getStartsAt(), round.getId()) + 1;
    }

    /** 여러 박람회의 회차 번호를 한 번에 매긴다.
     * sequenceOf 단건 호출을 회차 수만큼 반복하면 N+1 쿼리가 난다. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> sequencesOf(Set<Long> expoIds) {
        Map<Long, Integer> sequences = new HashMap<>();

        rounds.findByExpoIdInAndDeletedAtIsNull(expoIds).stream()
                .collect(Collectors.groupingBy(Round::getExpoId))
                .values()
                .forEach(perExpo -> {
                    List<Round> ordered = RoundSequence.ordered(perExpo);
                    for (int i = 0; i < ordered.size(); i++) {
                        sequences.put(ordered.get(i).getId(), i + 1);
                    }
                });
        return sequences;
    }

    /**
     * 캘린더·자연어 검색의 날짜 범위 조회(계약 2 roundsByDate). 날짜로 걸러진 목록은 그 자체로
     * 번호를 매기면 안 되므로, 결과에 등장하는 박람회 전체를 기준으로 sequencesOf 를 다시 돌린다.
     */
    @Transactional(readOnly = true)
    public List<InternalRoundResponse> roundsByDate(Collection<Long> expoIds, Instant from, Instant to,
                                                     boolean bookableOnly) {
        if (expoIds == null || expoIds.isEmpty()) {
            return List.of();
        }
        if (expoIds.size() > MAX_FEE_SUMMARY_IDS) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "too many expoIds: " + expoIds.size());
        }
        if (Duration.between(from,to).compareTo(MAX_DATE_RANGE)>0){
            throw new ApiException(ErrorCode.INVALID_REQUEST,"date range too wide: " + from + " ~ " + to);
        }

        List<Round> found = rounds.findByExpoIdInAndDateRange(expoIds, from, to, bookableOnly, clock.instant());

        Set<Long> expoIdsOf = found.stream().map(Round::getExpoId).collect(Collectors.toSet());
        Map<Long, Integer> sequences = sequencesOf(expoIdsOf);

        return found.stream()
                .map(round -> InternalRoundResponse.from(round, sequences.get(round.getId())))
                .toList();
    }

    /**
     * 목록 배지용 유료/무료 판정(계약 2 feeSummaries). 예약 가능한 회차 중 fee > 0 이 하나라도 있으면 유료다.
     * 예약 가능한 회차가 0개인 박람회는 결과에서 빠져 호출부가 배지를 숨긴다.
     */
    @Transactional(readOnly = true)
    public List<ExpoFeeSummaryResponse> feeSummaries(Collection<Long> expoIds) {
        if (expoIds == null || expoIds.isEmpty()) {
            return List.of();
        }
        if (expoIds.size() > MAX_FEE_SUMMARY_IDS) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "too many expoIds: " + expoIds.size());
        }
        Instant now = clock.instant();
        Set<Long> paid = Set.copyOf(rounds.findExpoIdsWithPaidOpenRounds(expoIds, now));
        return rounds.findExpoIdsWithOpenRounds(expoIds, now).stream()
                .map(expoId -> new ExpoFeeSummaryResponse(expoId, paid.contains(expoId)))
                .toList();
    }

    /** 박람회별 가장 가까운 모집 마감일을 한 번에 조회한다. expoId에 해당하는 값이 없으면 결과에서 빠진다. */
    @Transactional(readOnly = true)
    public Map<Long, Instant> nearestDeadlines(Collection<Long> expoIds) {
        if (expoIds == null || expoIds.isEmpty()) {
            return Map.of();
        }
        if (expoIds.size() > MAX_FEE_SUMMARY_IDS) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "too many expoIds: " + expoIds.size());
        }
        Map<Long, Instant> result = new HashMap<>();
        rounds.findNearestDeadlinesByExpoIds(expoIds, clock.instant())
                .forEach(v -> result.put(v.expoId(), v.nearestEndsAt()));
        return result;
    }

    /**
     * 마감일 기준 정렬 후 페이지 슬라이싱.
     * 마감일 있는 박람회(오름차순) → 마감일 없는 박람회 순으로 반환한다.
     */
    @Transactional(readOnly = true)
    public DeadlineSortResult deadlineSort(Collection<Long> expoIds, int page, int size) {
        if (expoIds == null || expoIds.isEmpty()) {
            return new DeadlineSortResult(List.of(), 0);
        }
        Map<Long, Instant> deadlineMap = new HashMap<>();
        rounds.findNearestDeadlinesByExpoIds(expoIds, clock.instant())
                .forEach(v -> deadlineMap.put(v.expoId(), v.nearestEndsAt()));

        List<Long> withDeadline = expoIds.stream()
                .filter(deadlineMap::containsKey)
                .sorted(Comparator.comparing(deadlineMap::get))
                .toList();
        List<Long> noDeadline = expoIds.stream()
                .filter(id -> !deadlineMap.containsKey(id))
                .toList();

        List<Long> sorted = new ArrayList<>(withDeadline);
        sorted.addAll(noDeadline);

        int from = (page - 1) * size;
        int to = Math.min(from + size, sorted.size());
        List<Long> slice = from >= sorted.size() ? List.of() : sorted.subList(from, to);

        return new DeadlineSortResult(slice, sorted.size());
    }

    @Transactional(readOnly = true)
    public boolean existsByExpo(Long expoId) {
        return rounds.existsByExpoIdAndDeletedAtIsNull(expoId);
    }

    @Transactional(readOnly = true)
    public List<Long> finishedExpoIds(Instant before, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_FINISHED_EXPO_LIMIT);
        return rounds.findExpoIdsWithAllRoundsEndedBefore(before, PageRequest.of(0, size));
    }


    /**
     * 조회한 박람회를 그대로 돌려준다 - 삭제 경로가 status 를 다시 묻지 않게 한다.
     */
    private ExpoSummary requireOwnership(Long expoId, AuthenticatedUser user) {
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        if (!ROLE_ORGANIZER.equals(user.role())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "ORGANIZER role required");
        }

        ExpoSummary expo = expoClient.getExpo(expoId);
        if (!Objects.equals(expo.channelOwnerId(), user.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "not the owner of expo " + expoId);
        }
        return expo;
    }
}
