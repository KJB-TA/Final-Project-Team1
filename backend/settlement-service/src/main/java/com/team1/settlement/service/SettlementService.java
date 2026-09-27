package com.team1.settlement.service;

import com.team1.settlement.client.ExpoDirectoryClient;
import com.team1.settlement.client.ExpoPromotionPaymentClient;
import com.team1.settlement.client.ExpoPromotionPaymentItem;
import com.team1.settlement.client.ReservationPaymentClient;
import com.team1.settlement.client.ReservationPaymentItem;
import com.team1.settlement.dto.AdminSettlementResponse;
import com.team1.settlement.dto.CategoryRanking;
import com.team1.settlement.dto.ExpoRanking;
import com.team1.settlement.dto.SettlementBucket;
import com.team1.settlement.dto.SettlementPeriod;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SettlementService {

    private static final double FEE_RATE = 0.10;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int TOP_EXPO_LIMIT = 5;
    private static final String UNKNOWN_CATEGORY = "기타";

    private final ReservationPaymentClient reservationPaymentClient;
    private final ExpoPromotionPaymentClient expoPromotionPaymentClient;
    private final ExpoDirectoryClient expoDirectoryClient;
    private final SettlementInsightService settlementInsightService;

    public SettlementService(ReservationPaymentClient reservationPaymentClient,
                             ExpoPromotionPaymentClient expoPromotionPaymentClient,
                             ExpoDirectoryClient expoDirectoryClient,
                             SettlementInsightService settlementInsightService) {
        this.reservationPaymentClient = reservationPaymentClient;
        this.expoPromotionPaymentClient = expoPromotionPaymentClient;
        this.expoDirectoryClient = expoDirectoryClient;
        this.settlementInsightService = settlementInsightService;
    }

    /**
     * includeSummary 는 화면이 실제로 보여줄 조회 1건에서만 true 로 둔다 - 전기간 비교용,
     * 당일 모드의 달력용 같은 보조 호출까지 매번 요약을 만들면 Gemini 호출이 2~3배로 는다.
     */
    public AdminSettlementResponse getSettlement(SettlementPeriod period, LocalDate date, boolean includeSummary) {
        LocalDate start = rangeStart(period, date);
        LocalDate end = rangeEnd(period, start);

        Instant from = start.atStartOfDay(KST).toInstant();
        Instant to = end.plusDays(1).atStartOfDay(KST).toInstant();

        List<ReservationPaymentItem> reservationPayments = reservationPaymentClient.getPayments(from, to);
        List<ExpoPromotionPaymentItem> promotionPayments = expoPromotionPaymentClient.getPayments(from, to);

        // 받아온 결제에는 "결제만 이 기간", "환불만 이 기간", "둘 다 이 기간" 이 섞여 있다. 사건 시각으로 나눈다.
        Window window = new Window(from, to);
        List<Event> events = new ArrayList<>();
        for (ReservationPaymentItem item : reservationPayments) {
            events.add(new Event(Source.RESERVATION, item.expoId(), item.amount(), item.paidAt(), item.cancelledAt()));
        }
        for (ExpoPromotionPaymentItem item : promotionPayments) {
            events.add(new Event(Source.PROMOTION, item.expoId(), item.amount(), item.paidAt(), item.cancelledAt()));
        }

        long reservationRevenue = sumRevenue(events, Source.RESERVATION, window);
        long reservationRefund = sumRefund(events, Source.RESERVATION, window);
        long promotionRevenue = sumRevenue(events, Source.PROMOTION, window);
        long promotionRefund = sumRefund(events, Source.PROMOTION, window);

        long totalRevenue = reservationRevenue + promotionRevenue;
        long totalRefund = reservationRefund + promotionRefund;
        long netRevenue = totalRevenue - totalRefund;
        long platformFee = Math.round(netRevenue * FEE_RATE);

        int reservationPaidCount = countRevenue(events, Source.RESERVATION, window);
        int reservationRefundCount = countRefund(events, Source.RESERVATION, window);
        int promotionPaidCount = countRevenue(events, Source.PROMOTION, window);
        int promotionRefundCount = countRefund(events, Source.PROMOTION, window);

        List<SettlementBucket> buckets = buildBuckets(period, start, end, events, window);
        Map<Long, Long> revenueByExpo = revenueByExpo(events, window);
        List<ExpoRanking> topExpos = buildTopExpos(revenueByExpo);
        List<CategoryRanking> topCategories = buildTopCategories(revenueByExpo);

        String aiSummary = includeSummary
                ? settlementInsightService.summarize(start + " ~ " + end, totalRevenue, totalRefund, netRevenue,
                        topExpos, topCategories, buckets)
                : null;

        return new AdminSettlementResponse(
                period, start.toString(), end.toString(),
                totalRevenue, totalRefund, netRevenue, platformFee, FEE_RATE,
                reservationRevenue, reservationRefund, promotionRevenue, promotionRefund,
                reservationPaidCount, reservationRefundCount, promotionPaidCount, promotionRefundCount,
                buckets, topExpos, topCategories, aiSummary);
    }

    private LocalDate rangeStart(SettlementPeriod period, LocalDate date) {
        return switch (period) {
            case DAY -> date;
            case WEEK -> date.with(DayOfWeek.MONDAY);
            case MONTH -> YearMonth.from(date).atDay(1);
            case YEAR -> LocalDate.of(date.getYear(), 1, 1);
        };
    }

    private LocalDate rangeEnd(SettlementPeriod period, LocalDate start) {
        return switch (period) {
            case DAY -> start;
            case WEEK -> start.plusDays(6);
            case MONTH -> YearMonth.from(start).atEndOfMonth();
            case YEAR -> LocalDate.of(start.getYear(), 12, 31);
        };
    }

    /**
     * DAY·WEEK·MONTH 는 하루 단위로, YEAR 는 달 단위로 쪼갠다 - 1년을 365칸 달력으로 그리는 건
     * 의미가 없고, 화면의 달력·클릭 상세는 MONTH 뷰에서만 쓴다.
     *
     * <p>매출은 결제 시각, 환불은 환불 시각이 속한 칸에 넣는다 - 합계와 같은 기준이다.
     */
    private List<SettlementBucket> buildBuckets(SettlementPeriod period, LocalDate start, LocalDate end,
                                                List<Event> events, Window window) {
        boolean byMonth = period == SettlementPeriod.YEAR;
        Map<String, long[]> byLabel = new LinkedHashMap<>(); // [revenue, refund]

        if (byMonth) {
            for (int m = 1; m <= 12; m++) {
                byLabel.put(YearMonth.of(start.getYear(), m).format(MONTH_LABEL), new long[2]);
            }
        } else {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                byLabel.put(d.format(DAY_LABEL), new long[2]);
            }
        }

        for (Event e : events) {
            if (e.isRevenueIn(window)) addTo(byLabel, label(e.paidAt(), byMonth), 0, e.amount());
            if (e.isRefundIn(window)) addTo(byLabel, label(e.cancelledAt(), byMonth), 1, e.amount());
        }

        List<SettlementBucket> buckets = new ArrayList<>();
        byLabel.forEach((label, rev) -> buckets.add(new SettlementBucket(label, rev[0], rev[1], rev[0] - rev[1])));
        return buckets;
    }

    private String label(Instant at, boolean byMonth) {
        return byMonth ? MONTH_LABEL.format(at.atZone(KST)) : DAY_LABEL.format(at.atZone(KST));
    }

    private void addTo(Map<String, long[]> byLabel, String label, int index, long amount) {
        long[] slot = byLabel.get(label);
        if (slot != null) slot[index] += amount;
    }

    /** 랭킹은 환불을 빼지 않은, 이 기간에 결제된 총액 기준이다 - "매출" 이라는 이름 그대로. */
    private Map<Long, Long> revenueByExpo(List<Event> events, Window window) {
        Map<Long, Long> revenueByExpo = new LinkedHashMap<>();
        for (Event e : events) {
            if (e.isRevenueIn(window) && e.expoId() != null) {
                revenueByExpo.merge(e.expoId(), (long) e.amount(), Long::sum);
            }
        }
        return revenueByExpo;
    }

    private List<ExpoRanking> buildTopExpos(Map<Long, Long> revenueByExpo) {
        if (revenueByExpo.isEmpty()) return List.of();

        List<Map.Entry<Long, Long>> sorted = revenueByExpo.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(TOP_EXPO_LIMIT)
                .toList();
        Map<Long, String> titles = expoDirectoryClient.titles(sorted.stream().map(Map.Entry::getKey).toList());

        return sorted.stream()
                .map(e -> new ExpoRanking(e.getKey(), titles.getOrDefault(e.getKey(), ""), e.getValue()))
                .toList();
    }

    private List<CategoryRanking> buildTopCategories(Map<Long, Long> revenueByExpo) {
        if (revenueByExpo.isEmpty()) return List.of();

        Map<Long, String> categories = expoDirectoryClient.categories(revenueByExpo.keySet());
        Map<String, Long> revenueByCategory = new LinkedHashMap<>();
        revenueByExpo.forEach((expoId, revenue) ->
                revenueByCategory.merge(categories.getOrDefault(expoId, UNKNOWN_CATEGORY), revenue, Long::sum));

        return revenueByCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new CategoryRanking(e.getKey(), e.getValue()))
                .toList();
    }

    private long sumRevenue(List<Event> events, Source source, Window window) {
        return events.stream().filter(e -> e.source() == source && e.isRevenueIn(window)).mapToLong(Event::amount).sum();
    }

    private long sumRefund(List<Event> events, Source source, Window window) {
        return events.stream().filter(e -> e.source() == source && e.isRefundIn(window)).mapToLong(Event::amount).sum();
    }

    private int countRevenue(List<Event> events, Source source, Window window) {
        return (int) events.stream().filter(e -> e.source() == source && e.isRevenueIn(window)).count();
    }

    private int countRefund(List<Event> events, Source source, Window window) {
        return (int) events.stream().filter(e -> e.source() == source && e.isRefundIn(window)).count();
    }

    private enum Source { RESERVATION, PROMOTION }

    /** [from, to) 반열린 구간 - 경계 시각의 결제가 두 기간에 동시에 잡히지 않게 한다. */
    private record Window(Instant from, Instant to) {
        boolean contains(Instant at) {
            return at != null && !at.isBefore(from) && at.isBefore(to);
        }
    }

    /** 결제 1건을 "결제 사건" 과 "환불 사건" 두 시각으로 본다. 결제된 적 없는 취소는 환불이 아니다. */
    private record Event(Source source, Long expoId, int amount, Instant paidAt, Instant cancelledAt) {
        boolean isRevenueIn(Window window) {
            return window.contains(paidAt);
        }

        boolean isRefundIn(Window window) {
            return paidAt != null && window.contains(cancelledAt);
        }
    }
}
