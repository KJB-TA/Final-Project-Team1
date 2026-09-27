package com.team1.expo.client;

import com.team1.expo.common.TraceId;
import com.team1.expo.common.exception.BusinessException;
import com.team1.expo.common.exception.ErrorCode;
import com.team1.expo.expo.dto.DeadlineSortResult;
import com.team1.expo.expo.dto.ExpoFeeView;
import com.team1.expo.expo.dto.NearestDeadlineView;
import com.team1.expo.expo.dto.RoundView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class RestClientRoundClient implements RoundClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientRoundClient.class);

    /**
     * 한 번에 보낼 expoId 개수. 예약-Service 가 200 을 넘으면 400 으로 거절한다.
     *
     * <p>목록 조회는 공개 박람회 <b>전체</b>를 넘긴다. 박람회가 200 개를 넘는 순간
     * 배지가 사라지고(fee-summary) 모집마감일순이 503 이 되므로, 호출부가 아니라
     * 여기서 잘라 보낸다.
     */
    private static final int BATCH_SIZE = 200;

    private final RestClient restClient;
    private final String internalToken;

    public RestClientRoundClient(RestClient reservationRestClient,
                                 @Value("${internal.token}") String internalToken) {
        this.restClient = reservationRestClient;
        this.internalToken = internalToken;
    }

    @Override
    public boolean existsByExpo(Long expoId) {
        try {
            ExistsResponse body = restClient.get()
                    .uri("/internal/v1/rounds/exists?expoId={expoId}", expoId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken)
                    .header(TraceId.HEADER, TraceId.get())
                    .retrieve()
                    .body(ExistsResponse.class);

            if (body == null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            return body.exists();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("roundsExists 호출 실패 expoId={} traceId={}", expoId, TraceId.get(), e);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public List<RoundView> listByExpo(Long expoId) {
        try {
            RoundView[] rounds = restClient.get()
                    .uri("/internal/v1/rounds?expoId={expoId}", expoId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken)
                    .header(TraceId.HEADER, TraceId.get())
                    .retrieve()
                    .body(RoundView[].class);

            return rounds == null ? List.of() : List.of(rounds);
        } catch (Exception e) {
            log.warn("listRoundsByExpo 호출 실패 expoId={} traceId={}", expoId, TraceId.get(), e);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public List<ExpoFeeView> feeSummaries(List<Long> expoIds) {
        if (expoIds.isEmpty()) {
            return List.of();
        }
        List<ExpoFeeView> all = new java.util.ArrayList<>();
        for (int start = 0; start < expoIds.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, expoIds.size());
            all.addAll(fetchFeeSummaries(expoIds.subList(start, end)));
        }
        return all;
    }

    private List<ExpoFeeView> fetchFeeSummaries(List<Long> expoIds) {
        // expoIds 가 Long 이라 인코딩할 문자가 없다. 반복 파라미터로 붙여 콤마 구분 모호함을 피한다.
        String query = expoIds.stream().map(id -> "expoIds=" + id).collect(Collectors.joining("&"));
        try {
            ExpoFeeView[] summaries = restClient.get()
                    .uri("/internal/v1/rounds/fee-summary?" + query)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken)
                    .header(TraceId.HEADER, TraceId.get())
                    .retrieve()
                    .body(ExpoFeeView[].class);

            return summaries == null ? List.of() : List.of(summaries);
        } catch (Exception e) {
            log.warn("feeSummaries 호출 실패 count={} traceId={}", expoIds.size(), TraceId.get(), e);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public List<Long> finishedExpoIds(Instant before, long afterExpoId, int limit) {
        try {
            Long[] ids = restClient.get()
                    .uri("/internal/v1/rounds/finished-expos?before={before}&afterExpoId={afterExpoId}&limit={limit}",
                            before, afterExpoId, limit)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken)
                    .header(TraceId.HEADER, TraceId.get())
                    .retrieve()
                    .body(Long[].class);
            return ids == null ? List.of() : List.of(ids);
        } catch (Exception e) {
            log.warn("finishedExpoIds 호출 실패 traceId={}", TraceId.get(), e);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public Map<Long, Instant> nearestDeadlines(List<Long> expoIds) {
        if (expoIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Instant> all = new java.util.HashMap<>();
        for (int start = 0; start < expoIds.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, expoIds.size());
            all.putAll(fetchNearestDeadlines(expoIds.subList(start, end)));
        }
        return all;
    }

    private Map<Long, Instant> fetchNearestDeadlines(List<Long> expoIds) {
        String query = expoIds.stream().map(id -> "expoIds=" + id).collect(Collectors.joining("&"));
        try {
            NearestDeadlineView[] views = restClient.get()
                    .uri("/internal/v1/rounds/nearest-deadlines?" + query)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken)
                    .header(TraceId.HEADER, TraceId.get())
                    .retrieve()
                    .body(NearestDeadlineView[].class);
            if (views == null) {
                return Map.of();
            }
            return java.util.Arrays.stream(views)
                    .collect(Collectors.toMap(NearestDeadlineView::expoId, NearestDeadlineView::nearestEndsAt));
        } catch (Exception e) {
            log.warn("nearestDeadlines 호출 실패 count={} traceId={}", expoIds.size(), TraceId.get(), e);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public DeadlineSortResult deadlineSort(List<Long> expoIds, int page, int size) {
        if (expoIds.isEmpty()) {
            return new DeadlineSortResult(List.of(), 0);
        }
        try {
            DeadlineSortResult result = restClient.post()
                    .uri("/internal/v1/rounds/deadline-sort?page={page}&size={size}", page, size)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken)
                    .header(TraceId.HEADER, TraceId.get())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(expoIds)
                    .retrieve()
                    .body(DeadlineSortResult.class);
            return result != null ? result : new DeadlineSortResult(List.of(), 0);
        } catch (Exception e) {
            log.warn("deadlineSort 호출 실패 count={} traceId={}", expoIds.size(), TraceId.get(), e);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public Set<Long> expoIdsWithRoundsBetween(List<Long> expoIds, Instant from, Instant to, boolean bookableOnly) {
        if (expoIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> found = new HashSet<>();
        for (int start = 0; start < expoIds.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, expoIds.size());
            found.addAll(fetchExpoIdsByDate(expoIds.subList(start, end), from, to, bookableOnly));
        }
        return found;
    }

    private List<Long> fetchExpoIdsByDate(List<Long> expoIds, Instant from, Instant to, boolean bookableOnly) {
        String query = expoIds.stream().map(id -> "expoIds=" + id).collect(Collectors.joining("&"));
        try {
            RoundExpoIdView[] rounds = restClient.get()
                    .uri("/internal/v1/rounds/by-date?from={from}&to={to}&bookableOnly={bookableOnly}&" + query,
                            from, to, bookableOnly)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken)
                    .header(TraceId.HEADER, TraceId.get())
                    .retrieve()
                    .body(RoundExpoIdView[].class);
            if (rounds == null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            return java.util.Arrays.stream(rounds).map(RoundExpoIdView::expoId).toList();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("roundsByDate 호출 실패 count={} traceId={}", expoIds.size(), TraceId.get(), e);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private record ExistsResponse(boolean exists) {
    }

    /** by-date 응답에서 쓰는 값은 expoId 하나뿐이다. 나머지 필드는 Jackson 이 버린다. */
    private record RoundExpoIdView(Long expoId) {
    }
}
