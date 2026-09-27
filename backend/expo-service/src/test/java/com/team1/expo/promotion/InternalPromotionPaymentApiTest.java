package com.team1.expo.promotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.team1.expo.domain.promotion.ExpoPromotion;
import com.team1.expo.domain.promotion.ExpoPromotionRepository;
import com.team1.expo.domain.promotion.ExpoPaymentTransactionRepository;
import com.team1.expo.support.ApiTestSupport;
import com.team1.payment.PaymentTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InternalPromotionPaymentApiTest extends ApiTestSupport {

    @Autowired
    private ExpoPromotionRepository promotionRepository;
    @Autowired
    private ExpoPaymentTransactionRepository paymentTransactionRepository;

    private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");
    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO   = "2026-09-30T23:59:59Z";

    @Test
    @DisplayName("PAID·CANCELLED 건만 반환하고 expoId를 포함한다")
    void 정산_조회_PAID_CANCELLED_반환() {
        long expoId = createExpo();
        paidTx(expoId, BASE.plusSeconds(100));
        cancelledTx(expoId, BASE.plusSeconds(200));
        pendingTx(expoId);

        ResponseEntity<JsonNode> response = get(
                "/internal/expo-promotions/payments?from=" + FROM + "&to=" + TO,
                TEST_INTERNAL_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody();
        assertThat(data.isArray()).isTrue();

        // 다른 테스트 클래스가 남긴 트랜잭션도 함께 조회될 수 있으므로 우리 expo 기준으로 필터
        long paidCount = 0, cancelledCount = 0;
        for (JsonNode node : data) {
            assertThat(node.path("status").asText()).isNotEqualTo("PENDING"); // 전체 대상: PENDING 미포함
            if (node.path("expoId").asLong() != expoId) continue;
            switch (node.path("status").asText()) {
                case "PAID"      -> paidCount++;
                case "CANCELLED" -> cancelledCount++;
            }
            assertThat(node.path("paymentId").asText()).isNotBlank();
            assertThat(node.path("amount").asInt()).isEqualTo(9_900);
        }
        assertThat(paidCount).isGreaterThanOrEqualTo(1);
        assertThat(cancelledCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("날짜 범위 밖의 건은 반환하지 않는다")
    void 날짜_범위_외_미포함() {
        long expoId = createExpo();
        // 범위 밖
        paidTx(expoId, Instant.parse("2026-08-31T23:59:59Z"));

        ResponseEntity<JsonNode> response = get(
                "/internal/expo-promotions/payments?from=" + FROM + "&to=" + TO,
                TEST_INTERNAL_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 범위 내 PAID/CANCELLED가 없으므로 이 expoId는 결과에 없어야 함
        for (JsonNode node : response.getBody()) {
            assertThat(node.path("expoId").asLong()).isNotEqualTo(expoId);
        }
    }

    @Test
    @DisplayName("지난달 결제·이번 달 환불 건은 두 달 조회 모두에 나오고 cancelledAt 을 싣는다")
    void 결제월과_환불월_모두_반환() {
        long expoId = createExpo();
        paidThenCancelledTx(expoId, Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-09-05T00:00:00Z"));

        JsonNode august = get("/internal/expo-promotions/payments?from=2026-08-01T00:00:00Z&to=2026-09-01T00:00:00Z",
                TEST_INTERNAL_TOKEN).getBody();
        JsonNode september = get("/internal/expo-promotions/payments?from=" + FROM + "&to=" + TO,
                TEST_INTERNAL_TOKEN).getBody();

        assertThat(findByExpo(august, expoId)).isNotNull();
        JsonNode inSeptember = findByExpo(september, expoId);
        assertThat(inSeptember).isNotNull();
        assertThat(inSeptember.path("cancelledAt").asText()).startsWith("2026-09-05");
    }

    @Test
    @DisplayName("내부 토큰 없이 호출하면 401을 반환한다")
    void 내부_토큰_없이_호출_거절() {
        ResponseEntity<JsonNode> response = get(
                "/internal/expo-promotions/payments?from=" + FROM + "&to=" + TO,
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ──────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────

    private long createExpo() {
        long ownerId = uniqueUserId();
        String token = jwtFor(ownerId, "ORGANIZER");
        var ch = post("/api/v1/channels",
                """
                {"name":"%s","description":"정산 테스트"}
                """.formatted(uniqueName()), token);
        long channelId = ch.getBody().path("data").path("id").asLong();
        var expo = post("/api/v1/channels/" + channelId + "/expos",
                """
                {"title":"정산 테스트 박람회","category":"IT·전자","description":"설명","venue":"코엑스","region":"서울"}
                """, token);
        return expo.getBody().path("data").path("id").asLong();
    }

    private void paidTx(long expoId, Instant paidAt) {
        ExpoPromotion p = ExpoPromotion.create(expoId, 9_900, Clock.systemUTC());
        p.confirm(Clock.fixed(paidAt, java.time.ZoneOffset.UTC));
        promotionRepository.save(p);
        PaymentTransaction tx = PaymentTransaction.create(p.getId(), "PAY-" + p.getId(), 9_900, paidAt);
        tx.markPaid("PG-TX-" + p.getId(), "0000", paidAt);
        paymentTransactionRepository.save(tx);
    }

    private void cancelledTx(long expoId, Instant cancelledAt) {
        ExpoPromotion p = ExpoPromotion.create(expoId, 9_900, Clock.systemUTC());
        p.confirm(Clock.fixed(cancelledAt.minusSeconds(60), java.time.ZoneOffset.UTC));
        promotionRepository.save(p);
        PaymentTransaction tx = PaymentTransaction.create(p.getId(), "PAY-C-" + p.getId(), 9_900, cancelledAt.minusSeconds(60));
        tx.markPaid("PG-TX-C-" + p.getId(), "0000", cancelledAt.minusSeconds(60));
        tx.markCancelled(cancelledAt);
        paymentTransactionRepository.save(tx);
    }

    private void paidThenCancelledTx(long expoId, Instant paidAt, Instant cancelledAt) {
        ExpoPromotion p = ExpoPromotion.create(expoId, 9_900, Clock.systemUTC());
        p.confirm(Clock.fixed(paidAt, java.time.ZoneOffset.UTC));
        promotionRepository.save(p);
        PaymentTransaction tx = PaymentTransaction.create(p.getId(), "PAY-PC-" + p.getId(), 9_900, paidAt);
        tx.markPaid("PG-TX-PC-" + p.getId(), "0000", paidAt);
        tx.markCancelled(cancelledAt);
        paymentTransactionRepository.save(tx);
    }

    private JsonNode findByExpo(JsonNode list, long expoId) {
        for (JsonNode node : list) {
            if (node.path("expoId").asLong() == expoId) return node;
        }
        return null;
    }

    private void pendingTx(long expoId) {
        ExpoPromotion p = ExpoPromotion.create(expoId, 9_900, Clock.systemUTC());
        promotionRepository.save(p);
        paymentTransactionRepository.save(
                PaymentTransaction.create(p.getId(), "PAY-P-" + p.getId(), 9_900, BASE));
    }
}
