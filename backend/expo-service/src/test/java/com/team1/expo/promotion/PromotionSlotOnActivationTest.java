package com.team1.expo.promotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.team1.expo.domain.expo.ExpoRepository;
import com.team1.expo.domain.promotion.ExpoPaymentTransactionRepository;
import com.team1.expo.domain.promotion.ExpoPromotion;
import com.team1.expo.domain.promotion.ExpoPromotionRepository;
import com.team1.expo.domain.promotion.ExpoPromotionStatus;
import com.team1.expo.support.ApiTestSupport;
import com.team1.payment.PaymentStatus;
import com.team1.payment.PaymentTransaction;
import com.team1.payment.PgCancelResult;
import com.team1.payment.PgClient;
import com.team1.payment.PgInquiryResult;
import com.team1.payment.PgPaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// 남은 슬롯 1개에 두 명이 신청하고 둘 다 결제한 경우. 신청 때 검사만으로는 한도를 넘는다.
@TestPropertySource(properties = "banner.max-slots=1")
class PromotionSlotOnActivationTest extends ApiTestSupport {

    @MockBean
    private PgClient pgClient;

    @Autowired
    private ExpoPromotionRepository promotionRepository;
    @Autowired
    private ExpoPaymentTransactionRepository paymentTransactionRepository;
    @Autowired
    private ExpoRepository expoRepository;

    @Value("${portone.store-id}")
    private String storeId;
    @Value("${portone.channel-key}")
    private String channelKey;

    private String ownerToken;
    private long expoId;

    @BeforeEach
    void setUp() {
        // 슬롯 수는 전역 ACTIVE 개수로 센다. 다른 Test 가 남긴 배너가 섞이지 않게 비운다.
        paymentTransactionRepository.deleteAll();
        promotionRepository.deleteAll();

        ownerToken = jwtFor(uniqueUserId(), "ORGANIZER");
        ResponseEntity<JsonNode> channelRes = post("/api/v1/channels",
                """
                {"name":"%s","description":"슬롯 테스트 채널"}
                """.formatted(uniqueName()), ownerToken);
        long channelId = channelRes.getBody().path("data").path("id").asLong();
        ResponseEntity<JsonNode> expoRes = post("/api/v1/channels/" + channelId + "/expos",
                """
                {"title":"슬롯 테스트 박람회","category":"IT·전자","description":"설명","venue":"코엑스","region":"서울"}
                """, ownerToken);
        expoId = expoRes.getBody().path("data").path("id").asLong();
        var expo = expoRepository.findById(expoId).orElseThrow();
        expo.publish();
        expoRepository.save(expo);
    }

    @Test
    @DisplayName("결제 확정 시점에 슬롯이 가득 찼으면 켜지 않고 결제를 환불한다")
    void refundsWhenSlotsFilledBeforeActivation() {
        // 먼저 결제를 마친 다른 배너가 마지막 슬롯을 차지했다.
        ExpoPromotion other = ExpoPromotion.create(expoId, 9_900, Clock.systemUTC());
        other.confirm(Clock.systemUTC());
        promotionRepository.save(other);

        ExpoPromotion late = promotionRepository.save(ExpoPromotion.create(expoId, 9_900, Clock.systemUTC()));
        PaymentTransaction tx = paymentTransactionRepository.save(PaymentTransaction.create(
                late.getId(), "BE24-01-SLOT" + late.getId(), 9_900, Instant.now()));
        when(pgClient.inquire(tx.getPaymentId())).thenReturn(new PgInquiryResult(
                PgPaymentStatus.PAID, 9_900, "pg-tx-slot", "PAID", null, storeId, channelKey));
        when(pgClient.cancel(anyString(), anyInt(), any())).thenReturn(new PgCancelResult(true, "SUCCEEDED"));

        ResponseEntity<JsonNode> response = post(
                "/api/v1/expo-promotions/" + late.getId() + "/payment", "", ownerToken);

        assertThat(response.getBody().path("data").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(promotionRepository.findById(late.getId()).orElseThrow().getStatus())
                .isEqualTo(ExpoPromotionStatus.CANCELLED);
        assertThat(paymentTransactionRepository.findByRefId(late.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
        assertThat(promotionRepository.countByStatus(ExpoPromotionStatus.ACTIVE)).isEqualTo(1);
    }
}
