package com.team1.reservation.round.controller;

import com.team1.reservation.round.service.RoundService;
import com.team1.reservation.round.dto.DeadlineSortResult;
import com.team1.reservation.round.dto.ExistsResponse;
import com.team1.reservation.round.dto.ExpoFeeSummaryResponse;
import com.team1.reservation.round.dto.InternalRoundResponse;
import com.team1.reservation.round.dto.NearestDeadlineView;
import com.team1.reservation.round.entity.Round;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;


@RestController
@RequestMapping("/internal/v1/rounds")
public class InternalRoundController {

    private final RoundService roundService;

    public InternalRoundController(RoundService roundService) {
        this.roundService = roundService;
    }


    @GetMapping("/exists")
    public ExistsResponse exists(@RequestParam Long expoId) {
        return new ExistsResponse(roundService.existsByExpo(expoId));
    }


    // 단건 조회(계약 2 getRoundInternal). 숫자만 받아 /exists·/finished-expos 와 겹치지 않게 한다.
    @GetMapping("/{roundId:\\d+}")
    public InternalRoundResponse get(@PathVariable Long roundId) {
        Round round = roundService.getById(roundId);
        return InternalRoundResponse.from(round, roundService.sequenceOf(round));
    }


    @GetMapping
    public List<InternalRoundResponse> list(@RequestParam Long expoId) {
        return InternalRoundResponse.listOf(roundService.listByExpo(expoId));
    }

    // 캘린더(AI 일정 추천) 자연어 검색의 날짜 필터(계약 roundsByDate). fail-closed -
    // 날짜 조건이 있는 조회라 실패를 감추면 결과가 틀린 걸 호출부가 모른다.
    @GetMapping("/by-date")
    public List<InternalRoundResponse> byDate(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam List<Long> expoIds,
            @RequestParam(defaultValue = "false") boolean bookableOnly
    ){
        return roundService.roundsByDate(expoIds,from,to,bookableOnly);
    }


    // 목록 배지용 일괄 조회(계약 2 feeSummaries). 박람회 수만큼 호출하지 않으려고 한 번에 받는다.
    @GetMapping("/fee-summary")
    public List<ExpoFeeSummaryResponse> feeSummary(@RequestParam List<Long> expoIds) {
        return roundService.feeSummaries(expoIds);
    }


    @GetMapping("/finished-expos")
    public List<Long> finishedExpos(@RequestParam Instant before,
                                    @RequestParam(defaultValue = "0") long afterExpoId,
                                    @RequestParam(defaultValue = "500") int limit) {
        return roundService.finishedExpoIds(before, afterExpoId, limit);
    }

    /** 박람회별 가장 가까운 모집 마감일 일괄 조회. 모집마감일순 정렬 전용. */
    @GetMapping("/nearest-deadlines")
    public List<NearestDeadlineView> nearestDeadlines(@RequestParam List<Long> expoIds) {
        return roundService.nearestDeadlines(expoIds).entrySet().stream()
                .map(e -> new NearestDeadlineView(e.getKey(), e.getValue()))
                .toList();
    }

    /** 마감일 기준 정렬 + 페이지 슬라이싱. 모집마감일순 목록 페이지네이션 전용. POST body로 받아 URI 길이 제한을 피한다. */
    @PostMapping("/deadline-sort")
    public DeadlineSortResult deadlineSort(
            @RequestBody List<Long> expoIds,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        return roundService.deadlineSort(expoIds, page, size);
    }
}
