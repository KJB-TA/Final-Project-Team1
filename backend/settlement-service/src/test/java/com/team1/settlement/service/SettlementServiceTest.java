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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 대시보드 개편(당일/주간/월간/연간 + 달력용 일자별 집계 + 박람회·카테고리 랭킹) 검증.
 * 기존엔 "그 달/그 해 합계 하나"만 냈는데, 이제 화면이 달력·추이그래프를 그릴 수 있게
 * 일자별(연간은 월별)로 쪼갠 배열과 랭킹을 함께 낸다.
 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock
    private ReservationPaymentClient reservationPaymentClient;
    @Mock
    private ExpoPromotionPaymentClient expoPromotionPaymentClient;
    @Mock
    private ExpoDirectoryClient expoDirectoryClient;
    @Mock
    private SettlementInsightService settlementInsightService;

    private SettlementService service;

    @BeforeEach
    void setUp() {
        service = new SettlementService(reservationPaymentClient, expoPromotionPaymentClient, expoDirectoryClient, settlementInsightService);
        // 매출이 없는 테스트에서는 랭킹 집계가 조회 자체를 건너뛰어(비어있으면 호출 안 함) 이 기본값들이
        // 안 쓰일 수 있다 - lenient 로 안전한 기본값 취급한다.
        org.mockito.Mockito.lenient().when(expoPromotionPaymentClient.getPayments(any(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(expoDirectoryClient.titles(any())).thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(expoDirectoryClient.categories(any())).thenReturn(Map.of());
    }

    private ReservationPaymentItem paid(String day, int amount, Long expoId) {
        Instant at = LocalDate.parse(day).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).plusHours(12).toInstant();
        return new ReservationPaymentItem("p-" + day, amount, "PAID", at, null, at, 1L, expoId);
    }

    // 지난달(2026-08-01)에 결제되고 day 에 환불된 건 - 조회 기간엔 환불만 들어온다.
    private ReservationPaymentItem cancelled(String day, int amount, Long expoId) {
        return paidThenCancelled("2026-08-01", day, amount, expoId);
    }

    private ReservationPaymentItem paidThenCancelled(String paidDay, String cancelDay, int amount, Long expoId) {
        Instant paidAt = noonKst(paidDay);
        Instant cancelledAt = noonKst(cancelDay);
        return new ReservationPaymentItem("c-" + cancelDay, amount, "CANCELLED", paidAt, cancelledAt, cancelledAt, 1L, expoId);
    }

    private Instant noonKst(String day) {
        return LocalDate.parse(day).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).plusHours(12).toInstant();
    }

    @Test
    @DisplayName("MONTH 조회는 그 달 1일부터 말일까지를 일자별로 쪼갠다")
    void monthBucketsByDay() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                paid("2026-09-01", 10000, 1L),
                paid("2026-09-15", 20000, 1L),
                cancelled("2026-09-15", 5000, 1L)));

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 20), false);

        assertThat(result.from()).isEqualTo("2026-09-01");
        assertThat(result.to()).isEqualTo("2026-09-30");
        assertThat(result.buckets()).hasSize(30);
        SettlementBucket day1 = findBucket(result.buckets(), "2026-09-01");
        SettlementBucket day15 = findBucket(result.buckets(), "2026-09-15");
        assertThat(day1.revenue()).isEqualTo(10000);
        assertThat(day15.revenue()).isEqualTo(20000);
        assertThat(day15.refund()).isEqualTo(5000);
        assertThat(day15.net()).isEqualTo(15000);
    }

    @Test
    @DisplayName("YEAR 조회는 1~12월로 쪼갠다")
    void yearBucketsByMonth() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                paid("2026-03-10", 30000, 1L),
                paid("2026-11-05", 70000, 1L)));

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.YEAR, LocalDate.of(2026, 6, 1), false);

        assertThat(result.buckets()).hasSize(12);
        assertThat(findBucket(result.buckets(), "2026-03").revenue()).isEqualTo(30000);
        assertThat(findBucket(result.buckets(), "2026-11").revenue()).isEqualTo(70000);
        assertThat(findBucket(result.buckets(), "2026-01").revenue()).isZero();
    }

    @Test
    @DisplayName("WEEK 조회는 월요일부터 일요일까지 7일이다")
    void weekRangeIsMondayToSunday() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of());

        // 2026-09-23 은 수요일
        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.WEEK, LocalDate.of(2026, 9, 23), false);

        assertThat(result.from()).isEqualTo("2026-09-21"); // 월
        assertThat(result.to()).isEqualTo("2026-09-27");   // 일
        assertThat(result.buckets()).hasSize(7);
    }

    @Test
    @DisplayName("DAY 조회는 그 하루만 잡는다")
    void dayRangeIsSingleDay() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of());

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.DAY, LocalDate.of(2026, 9, 23), false);

        assertThat(result.from()).isEqualTo("2026-09-23");
        assertThat(result.to()).isEqualTo("2026-09-23");
        assertThat(result.buckets()).hasSize(1);
    }

    @Test
    @DisplayName("박람회별 매출 랭킹은 예약+프로모션 매출을 합쳐 상위 5개만 낸다")
    void topExposCombinesReservationAndPromotionRevenue() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                paid("2026-09-01", 10000, 1L),
                paid("2026-09-02", 5000, 1L),
                paid("2026-09-03", 8000, 2L)));
        when(expoPromotionPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                new ExpoPromotionPaymentItem("promo-1", 20000, Instant.parse("2026-09-04T00:00:00Z"), null, "PAID", 2L)));
        when(expoDirectoryClient.titles(any())).thenReturn(Map.of(1L, "IT 박람회", 2L, "뷰티 박람회"));

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), false);

        // expo 2: 8000(예약) + 20000(프로모션) = 28000, expo 1: 10000+5000 = 15000
        assertThat(result.topExpos()).extracting(ExpoRanking::expoId).containsExactly(2L, 1L);
        assertThat(result.topExpos().get(0).revenue()).isEqualTo(28000);
        assertThat(result.topExpos().get(0).title()).isEqualTo("뷰티 박람회");
    }

    @Test
    @DisplayName("카테고리를 못 찾은 박람회는 기타로 묶는다")
    void unknownCategoryFallsBackToEtc() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                paid("2026-09-01", 10000, 1L),
                paid("2026-09-02", 20000, 2L)));
        when(expoDirectoryClient.categories(any())).thenReturn(Map.of(1L, "IT·전자"));

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), false);

        assertThat(result.topCategories()).extracting(CategoryRanking::category)
                .containsExactlyInAnyOrder("IT·전자", "기타");
        Optional<CategoryRanking> etc = result.topCategories().stream()
                .filter(c -> c.category().equals("기타")).findFirst();
        assertThat(etc).isPresent();
        assertThat(etc.get().revenue()).isEqualTo(20000);
    }

    @Test
    @DisplayName("결제·환불 건수를 센다")
    void countsPaidAndRefundedTransactions() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                paid("2026-09-01", 10000, 1L),
                paid("2026-09-02", 20000, 1L),
                cancelled("2026-09-03", 5000, 1L)));

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), false);

        assertThat(result.reservationPaidCount()).isEqualTo(2);
        assertThat(result.reservationRefundCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 조회는 KST 기준 그 달의 시작과 끝을 UTC Instant로 넘긴다")
    void queriesUseKstDayBoundaries() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of());

        service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), false);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(reservationPaymentClient).getPayments(from.capture(), to.capture());
        // KST 2026-09-01 00:00 = UTC 2026-08-31 15:00
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-08-31T15:00:00Z"));
        // KST 2026-10-01 00:00(다음날 시작, exclusive) = UTC 2026-09-30T15:00:00Z
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
    }

    @Test
    @DisplayName("includeSummary=false면 요약 서비스를 아예 안 부른다 - 보조 호출에서 Gemini 낭비 방지")
    void skipsInsightServiceWhenSummaryNotRequested() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of());

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), false);

        assertThat(result.aiSummary()).isNull();
        org.mockito.Mockito.verifyNoInteractions(settlementInsightService);
    }

    @Test
    @DisplayName("includeSummary=true면 요약 서비스 결과를 aiSummary에 담는다")
    void includesSummaryWhenRequested() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                paid("2026-09-01", 10000, 1L)));
        when(settlementInsightService.summarize(any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                any(), any(), any()))
                .thenReturn("이번 달 매출은 10,000원입니다.");

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), true);

        assertThat(result.aiSummary()).isEqualTo("이번 달 매출은 10,000원입니다.");
    }

    // ---- 사건 시각 기준 집계 (결제는 결제 시각, 환불은 환불 시각이 속한 기간) ----

    @Test
    @DisplayName("지난달 결제·이번 달 환불 - 지난달 매출은 그대로 남고, 이번 달엔 환불만 잡힌다")
    void refundDoesNotRewritePastRevenue() {
        ReservationPaymentItem item = paidThenCancelled("2026-08-20", "2026-09-05", 10000, 1L);
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of(item));

        AdminSettlementResponse august = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 8, 1), false);
        AdminSettlementResponse september = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), false);

        assertThat(august.totalRevenue()).isEqualTo(10000);
        assertThat(august.totalRefund()).isZero();
        assertThat(september.totalRevenue()).isZero();
        assertThat(september.totalRefund()).isEqualTo(10000);
    }

    @Test
    @DisplayName("같은 달에 결제하고 환불하면 매출과 환불이 둘 다 잡혀 순매출이 0 이다")
    void sameMonthPayAndRefundNetsToZero() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                paidThenCancelled("2026-09-03", "2026-09-10", 10000, 1L)));

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), false);

        assertThat(result.totalRevenue()).isEqualTo(10000);
        assertThat(result.totalRefund()).isEqualTo(10000);
        assertThat(result.netRevenue()).isZero();
        assertThat(findBucket(result.buckets(), "2026-09-03").revenue()).isEqualTo(10000);
        assertThat(findBucket(result.buckets(), "2026-09-10").refund()).isEqualTo(10000);
    }

    @Test
    @DisplayName("결제된 적 없이 취소된 건은 환불로 세지 않는다")
    void cancelWithoutPaymentIsNotRefund() {
        Instant at = noonKst("2026-09-10");
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                new ReservationPaymentItem("never-paid", 10000, "CANCELLED", null, at, at, 1L, 1L)));

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), false);

        assertThat(result.totalRefund()).isZero();
        assertThat(result.reservationRefundCount()).isZero();
    }

    @Test
    @DisplayName("환불이 실패해 재시도 중인 결제는 아직 매출이다")
    void refundFailedStillCountsAsRevenue() {
        Instant paidAt = noonKst("2026-09-10");
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                new ReservationPaymentItem("refund-failed", 10000, "REFUND_FAILED", paidAt, null, paidAt, 1L, 1L)));

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), false);

        assertThat(result.totalRevenue()).isEqualTo(10000);
        assertThat(result.totalRefund()).isZero();
    }

    @Test
    @DisplayName("프로모션 환불도 결제 시각이 아니라 환불 시각의 칸에 들어간다")
    void promotionRefundUsesCancelledAt() {
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of());
        when(expoPromotionPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                new ExpoPromotionPaymentItem("promo-c", 9900, noonKst("2026-09-02"), noonKst("2026-09-20"), "CANCELLED", 1L)));

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), false);

        assertThat(findBucket(result.buckets(), "2026-09-02").revenue()).isEqualTo(9900);
        assertThat(findBucket(result.buckets(), "2026-09-02").refund()).isZero();
        assertThat(findBucket(result.buckets(), "2026-09-20").refund()).isEqualTo(9900);
    }

    @Test
    @DisplayName("다음 기간 0시 정각 결제는 이번 기간에 넣지 않는다 - [from, to) 반열린 구간")
    void excludesPaymentAtExclusiveUpperBound() {
        Instant nextMonthStart = Instant.parse("2026-09-30T15:00:00Z"); // KST 2026-10-01 00:00
        when(reservationPaymentClient.getPayments(any(), any())).thenReturn(List.of(
                new ReservationPaymentItem("edge", 10000, "PAID", nextMonthStart, null, nextMonthStart, 1L, 1L)));

        AdminSettlementResponse result = service.getSettlement(SettlementPeriod.MONTH, LocalDate.of(2026, 9, 1), false);

        assertThat(result.totalRevenue()).isZero();
    }

    private SettlementBucket findBucket(List<SettlementBucket> buckets, String label) {
        return buckets.stream().filter(b -> b.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("bucket not found: " + label));
    }
}
