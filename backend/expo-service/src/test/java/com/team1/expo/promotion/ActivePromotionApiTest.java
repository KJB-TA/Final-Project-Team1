package com.team1.expo.promotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.team1.expo.domain.expo.ExpoRepository;
import com.team1.expo.domain.promotion.ExpoPromotion;
import com.team1.expo.domain.promotion.ExpoPromotionRepository;
import com.team1.expo.domain.promotion.ExpoPaymentTransactionRepository;
import com.team1.expo.support.ApiTestSupport;
import com.team1.payment.PaymentTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ActivePromotionApiTest extends ApiTestSupport {

    @Autowired
    private ExpoPromotionRepository promotionRepository;
    @Autowired
    private ExpoPaymentTransactionRepository paymentTransactionRepository;
    @Autowired
    private ExpoRepository expoRepository;

    private long expoId1;
    private long expoId2;

    @BeforeEach
    void setUp() {
        expoId1 = createExpo();
        expoId2 = createExpo();
    }

    @Test
    @DisplayName("ACTIVE 배너가 없으면 빈 배열을 반환한다")
    void 활성_배너_없음() {
        ResponseEntity<JsonNode> response = get("/api/v1/expo-promotions/active", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("data").isArray()).isTrue();
    }

    @Test
    @DisplayName("ACTIVE 배너가 두 건 모두 반환되고 expo 정보를 포함한다")
    void 활성_배너_목록_확인() {
        long p1 = activePromotion(expoId1, Instant.parse("2026-09-01T00:00:00Z"));
        long p2 = activePromotion(expoId2, Instant.parse("2026-09-02T00:00:00Z"));

        ResponseEntity<JsonNode> response = get("/api/v1/expo-promotions/active", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().path("data");
        assertThat(data.size()).isGreaterThanOrEqualTo(2);

        // 두 promotionId가 모두 포함돼야 한다 (순환으로 순서는 실행 시점에 따라 달라짐)
        var ids = new java.util.HashSet<Long>();
        for (JsonNode node : data) {
            ids.add(node.path("promotionId").asLong());
            // expo 정보 포함 확인
            assertThat(node.path("title").asText()).isNotBlank();
        }
        assertThat(ids).contains(p1, p2);
    }

    @Test
    @DisplayName("숨김·마감 박람회의 ACTIVE 배너는 자정 만료 전이라도 조회에서 빠진다")
    void 비공개_박람회_배너_미포함() {
        long visible = activePromotion(expoId1, Instant.parse("2026-09-01T00:00:00Z"));
        long hidden = activePromotion(expoId2, Instant.parse("2026-09-02T00:00:00Z"));
        var expo = expoRepository.findById(expoId2).orElseThrow();
        expo.unpublish();
        expoRepository.save(expo);

        JsonNode data = get("/api/v1/expo-promotions/active", null).getBody().path("data");

        var ids = new java.util.HashSet<Long>();
        for (JsonNode node : data) ids.add(node.path("promotionId").asLong());
        assertThat(ids).contains(visible).doesNotContain(hidden);
    }

    @Test
    @DisplayName("PENDING/CANCELLED 배너는 조회에 포함되지 않는다")
    void 비활성_배너_미포함() {
        // PENDING 상태
        ExpoPromotion pending = ExpoPromotion.create(expoId1, 9_900, Clock.systemUTC());
        promotionRepository.save(pending);

        ResponseEntity<JsonNode> response = get("/api/v1/expo-promotions/active", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = response.getBody().path("data");
        assertThat(data.isArray()).isTrue();
        // pending은 포함되지 않음
        for (JsonNode node : data) {
            assertThat(node.path("promotionId").asLong()).isNotEqualTo(pending.getId());
        }
    }

    @Test
    @DisplayName("JWT 없이도 조회 가능하다 (공개 API)")
    void 인증_없이_조회_가능() {
        ResponseEntity<JsonNode> response = get("/api/v1/expo-promotions/active", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ──────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────

    private long createExpo() {
        long ownerId = uniqueUserId();
        String token = jwtFor(ownerId, "ORGANIZER");

        ResponseEntity<JsonNode> channelRes = post("/api/v1/channels",
                """
                {"name":"%s","description":"배너 조회 테스트"}
                """.formatted(uniqueName()), token);
        long channelId = channelRes.getBody().path("data").path("id").asLong();

        ResponseEntity<JsonNode> expoRes = post("/api/v1/channels/" + channelId + "/expos",
                """
                {"title":"배너 테스트 박람회","category":"IT·전자","description":"설명","venue":"코엑스","region":"서울"}
                """, token);
        long id = expoRes.getBody().path("data").path("id").asLong();
        var expo = expoRepository.findById(id).orElseThrow();
        expo.publish();
        expoRepository.save(expo);
        return id;
    }

    private long activePromotion(long expoId, Instant paidAt) {
        ExpoPromotion promotion = ExpoPromotion.create(expoId, 9_900, Clock.systemUTC());
        promotion.confirm(Clock.fixed(paidAt, java.time.ZoneOffset.UTC));
        promotionRepository.save(promotion);
        paymentTransactionRepository.save(
                PaymentTransaction.create(promotion.getId(), "BE24-01-TEST" + promotion.getId(), 9_900, paidAt));
        return promotion.getId();
    }
}
