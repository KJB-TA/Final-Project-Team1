package com.team1.ticket.ticket.service;

import com.team1.ai.GeminiClient;
import com.team1.security.AuthenticatedUser;
import com.team1.ticket.client.ExpoClient;
import com.team1.ticket.client.ExpoSummary;
import com.team1.ticket.client.ReservationSummary;
import com.team1.ticket.client.ReservationSummaryClient;
import com.team1.ticket.common.ApiException;
import com.team1.ticket.common.ErrorCode;
import com.team1.ticket.ticket.dto.CheckinLogStat;
import com.team1.ticket.ticket.dto.CheckinReportResponse;
import com.team1.ticket.ticket.dto.CheckinSummaryItem;
import com.team1.ticket.ticket.entity.CheckinAction;
import com.team1.ticket.ticket.entity.CheckinLog;
import com.team1.ticket.ticket.entity.TicketStatus;
import com.team1.ticket.ticket.repository.CheckinLogRepository;
import com.team1.ticket.ticket.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 현장 체크인 결과를 집계하고 요약 문장을 붙인다(#259).
 *
 * <p>숫자는 규칙으로 계산하고 LLM 은 그 숫자를 사람 말로 바꾸는 데만 쓴다.
 * LLM 이 죽어도 주최자는 숫자를 봐야 하므로 요약만 비운다.
 */
@Service
public class CheckinReportService {

    private static final Logger log = LoggerFactory.getLogger(CheckinReportService.class);

    private static final String ROLE_ORGANIZER = "ORGANIZER";

    /** 호출량 집계·로그 단위. common-ai 가 기능별로 사용량을 센다. */
    private static final String FEATURE = "checkin-report";

    /** 주최자가 보는 시간대라 한국 시간으로 나눈다. 저장은 UTC 다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M/d");
    private static final DateTimeFormatter ROUND_START = DateTimeFormatter.ofPattern("M/d H시");

    private static final String PROMPT = """
            당신은 박람회 현장 운영 담당자를 돕는 분석가입니다.
            아래 수치를 바탕으로 한국어 요약을 2~3문장으로 쓰세요.
            조건: 첫 문장은 박람회 전체(모든 회차 합산) 결과로 쓰세요. 회차가 둘 이상이면 입장률이
            눈에 띄게 높거나 낮은 회차를 한 번 짚으세요. 수치를 그대로 인용하되 나열만 하지 말고,
            운영자가 다음에 참고할 점을 한 가지 덧붙이세요.
            추측으로 사실을 만들지 말고 주어진 수치 안에서만 쓰세요. JSON으로만 응답하세요.

            박람회: %s
            [전체 - 모든 회차 합산]
            예약 확정 인원: %d명
            실제 입장 인원: %d명 (입장률 %d%%)
            미입장(노쇼): %d명
            [회차별]
            %s
            [기타]
            날짜·시간대별 입장(한국 시간): %s
            처리 방법별 건수: %s
            체크인 되돌린 건수: %d건

            응답 형식: {"summary": "요약 문장"}
            """;

    private final TicketRepository ticketRepository;
    private final CheckinLogRepository checkinLogRepository;
    private final ExpoClient expoClient;
    private final ReservationSummaryClient reservationSummaryClient;
    private final GeminiClient geminiClient;

    public CheckinReportService(TicketRepository ticketRepository,
                                CheckinLogRepository checkinLogRepository,
                                ExpoClient expoClient,
                                ReservationSummaryClient reservationSummaryClient,
                                GeminiClient geminiClient) {
        this.ticketRepository = ticketRepository;
        this.checkinLogRepository = checkinLogRepository;
        this.expoClient = expoClient;
        this.reservationSummaryClient = reservationSummaryClient;
        this.geminiClient = geminiClient;
    }

    /** LLM 응답을 받는 그릇. 파싱은 common-ai 가 한다. */
    public record Summary(String summary) {
    }

    @Transactional(readOnly = true)
    public CheckinReportResponse getReport(Long expoId, AuthenticatedUser organizer) {
        ExpoSummary expo = verifyOwnership(expoId, organizer);

        Map<Long, Long> checkedInByRound = ticketRepository.sumCheckedInByRound(expoId, TicketStatus.USED).stream()
                .collect(Collectors.toMap(CheckinSummaryItem::roundId, CheckinSummaryItem::checkedIn, Long::sum));
        long checkedIn = checkedInByRound.values().stream().mapToLong(Long::longValue).sum();

        // 예약 수는 예약-Service 소유다. 못 받아오면 0 으로 두고 입장 수만 보여준다.
        List<ReservationSummary> reservations = reservationSummaryClient.findSummaries(expoId);
        int reserved = reservations.stream().mapToInt(ReservationSummary::confirmed).sum();
        int capacity = reservations.stream().mapToInt(ReservationSummary::capacity).sum();

        long noShow = noShow(reserved, checkedIn);
        int checkinRate = checkinRate(reserved, checkedIn);

        List<CheckinReportResponse.RoundCheckin> rounds = roundBreakdown(reservations, checkedInByRound);
        List<CheckinReportResponse.HourlyCheckin> hourly = hourlyDistribution(expoId);
        Map<String, Long> byMethod = methodCounts(expoId);
        long reverted = checkinLogRepository.countByAction(expoId, CheckinAction.CANCEL);

        String summary = summarize(expo.title(), reserved, checkedIn, checkinRate, noShow,
                rounds, hourly, byMethod, reverted);

        return new CheckinReportResponse(expoId, expo.title(), reserved, capacity, checkedIn, noShow,
                checkinRate, rounds, hourly, byMethod, reverted, summary);
    }

    /**
     * 회차별 예약 대비 입장. 예약-Service 가 회차를 시작 순서로 주므로 그 순서가 곧 회차 번호다
     * (예약-Service 의 회차 번호도 삭제되지 않은 회차의 시작 순서다).
     * 예약 현황을 못 받아왔으면 입장 기록이 있는 회차만 번호 없이 붙인다.
     */
    private List<CheckinReportResponse.RoundCheckin> roundBreakdown(List<ReservationSummary> reservations,
                                                                   Map<Long, Long> checkedInByRound) {
        List<CheckinReportResponse.RoundCheckin> rounds = new ArrayList<>();
        Set<Long> listed = new HashSet<>();

        for (int i = 0; i < reservations.size(); i++) {
            ReservationSummary r = reservations.get(i);
            long in = checkedInByRound.getOrDefault(r.roundId(), 0L);
            rounds.add(new CheckinReportResponse.RoundCheckin(r.roundId(), i + 1, r.startsAt(),
                    r.confirmed(), in, noShow(r.confirmed(), in), checkinRate(r.confirmed(), in)));
            listed.add(r.roundId());
        }

        checkedInByRound.entrySet().stream()
                .filter(e -> !listed.contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> rounds.add(new CheckinReportResponse.RoundCheckin(
                        e.getKey(), null, null, 0, e.getValue(), 0, 0)));
        return rounds;
    }

    private static long noShow(int reserved, long checkedIn) {
        return Math.max(reserved - checkedIn, 0);
    }

    private static int checkinRate(int reserved, long checkedIn) {
        return reserved > 0 ? (int) Math.round(checkedIn * 100.0 / reserved) : 0;
    }

    /** 입장 시각을 한국 시간 기준 날짜·시(0~23)로 모은다. 건수가 적어 메모리에서 센다. */
    private List<CheckinReportResponse.HourlyCheckin> hourlyDistribution(Long expoId) {
        Map<LocalDateTime, Long> counts = new TreeMap<>();
        for (CheckinLog entry : checkinLogRepository.findByExpoAndAction(expoId, CheckinAction.CHECK_IN)) {
            LocalDateTime hour = entry.getCreatedAt().atZone(KST).toLocalDateTime().truncatedTo(ChronoUnit.HOURS);
            counts.merge(hour, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(e -> new CheckinReportResponse.HourlyCheckin(
                        e.getKey().toLocalDate(), e.getKey().getHour(), e.getValue()))
                .toList();
    }

    private Map<String, Long> methodCounts(Long expoId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        checkinLogRepository.countByMethod(expoId, CheckinAction.CHECK_IN).stream()
                .sorted(Comparator.comparingLong(CheckinLogStat::count).reversed())
                .forEach(stat -> counts.put(stat.bucket(), stat.count()));
        return counts;
    }

    private String summarize(String title, int reserved, long checkedIn, int checkinRate, long noShow,
                             List<CheckinReportResponse.RoundCheckin> rounds,
                             List<CheckinReportResponse.HourlyCheckin> hourly,
                             Map<String, Long> byMethod, long reverted) {
        // 입장 기록이 없으면 요약할 것이 없다. LLM 을 부르지 않는다.
        if (checkedIn == 0) return null;
        if (!geminiClient.isAvailable()) return null;

        String roundText = rounds.isEmpty() ? "기록 없음" : rounds.stream()
                .map(CheckinReportService::roundLine)
                .collect(Collectors.joining("\n"));
        String hourlyText = hourly.stream()
                .map(h -> h.date().format(MONTH_DAY) + " " + h.hour() + "시 " + h.count() + "명")
                .reduce((a, b) -> a + ", " + b)
                .orElse("기록 없음");
        // 프롬프트에 enum 이름을 그대로 넘기면 요약 문장에 RESERVATION_NO 가 박혀 나온다.
        String methodText = byMethod.isEmpty() ? "기록 없음" : byMethod.entrySet().stream()
                .map(e -> methodLabel(e.getKey()) + " " + e.getValue() + "건")
                .collect(java.util.stream.Collectors.joining(", "));

        String prompt = PROMPT.formatted(title, reserved, checkedIn, checkinRate, noShow,
                roundText, hourlyText, methodText, reverted);

        Summary result = geminiClient.generateJson(FEATURE, prompt, Summary.class);
        if (result == null || result.summary() == null || result.summary().isBlank()) {
            log.info("checkin report summary unavailable expoId title={}", title);
            return null;
        }
        return result.summary().trim();
    }

    /** 예: "2회차(11/20 10시): 예약 30명, 입장 24명, 입장률 80%" */
    private static String roundLine(CheckinReportResponse.RoundCheckin r) {
        String name = r.sequence() == null ? "회차 #" + r.roundId() : r.sequence() + "회차";
        String when = r.startsAt() == null ? "" : "(" + r.startsAt().atZone(KST).format(ROUND_START) + ")";
        return "%s%s: 예약 %d명, 입장 %d명, 입장률 %d%%".formatted(
                name, when, r.reserved(), r.checkedIn(), r.checkinRate());
    }

    /** 요약 문장에 쓸 이름. 화면(byMethod)은 원래 값을 그대로 받아 자체 표기를 쓴다. */
    private static String methodLabel(String method) {
        return switch (method) {
            case "QR" -> "QR";
            case "RESERVATION_NO" -> "예약번호";
            default -> "방법 미기록";
        };
    }

    /** 자기 박람회만 볼 수 있다. 소유권은 박람회-Service 만 안다. */
    private ExpoSummary verifyOwnership(Long expoId, AuthenticatedUser organizer) {
        if (organizer == null || !ROLE_ORGANIZER.equals(organizer.role())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "organizer role required");
        }
        ExpoSummary expo = expoClient.getExpo(expoId);
        if (!expo.channelOwnerId().equals(organizer.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "not the owner of this expo");
        }
        return expo;
    }
}
