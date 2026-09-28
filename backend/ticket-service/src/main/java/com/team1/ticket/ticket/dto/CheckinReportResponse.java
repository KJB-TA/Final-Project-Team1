package com.team1.ticket.ticket.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 현장 체크인 결과 요약(#259). 숫자는 모두 박람회 전체(모든 회차 합산)이고, 회차별 내역은 rounds 에 있다.
 *
 * @param summary   LLM 이 쓴 요약 문장. 실패하면 null 이고 숫자는 그대로 내려간다
 * @param rounds    회차별 예약·입장. 회차 시작 순서이며 예약 현황을 못 받아오면 입장 기록이 있는 회차만 온다
 * @param hourly    날짜·시간대별 입장 인원(한국 시간 기준). 입장이 없는 시간대는 빠진다
 * @param byMethod  처리 방법별 건수. 화면이 방법을 안 보냈으면 UNKNOWN
 * @param reverted  되돌린 체크인 건수
 */
public record CheckinReportResponse(Long expoId,
                                    String expoTitle,
                                    int reserved,
                                    int capacity,
                                    long checkedIn,
                                    long noShow,
                                    int checkinRate,
                                    List<RoundCheckin> rounds,
                                    List<HourlyCheckin> hourly,
                                    Map<String, Long> byMethod,
                                    long reverted,
                                    String summary) {

    /**
     * @param sequence 회차 번호(시작 순서, 1부터). 예약 현황을 못 받아와 순서를 모르면 null
     * @param startsAt 회차 시작 시각. sequence 와 같은 이유로 null 일 수 있다
     */
    public record RoundCheckin(Long roundId,
                               Integer sequence,
                               Instant startsAt,
                               int reserved,
                               long checkedIn,
                               long noShow,
                               int checkinRate) {
    }

    /**
     * 여러 날에 걸친 회차를 시(時)만으로 모으면 다른 날의 같은 시간대가 합쳐져
     * 없던 혼잡이 생긴다. 날짜까지 나눠 센다.
     */
    public record HourlyCheckin(LocalDate date, int hour, long count) {
    }
}
