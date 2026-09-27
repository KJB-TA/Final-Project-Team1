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
import com.team1.payment.PgCreateResult;
import com.team1.payment.PgInquiryResult;
import com.team1.payment.PgPaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 결제는 끝났는데 웹훅이 오기 전에 재신청한 경우. 이전 PENDING 을 그냥 취소하면 돈만 남는다.
class PromotionReapplyStalePendingTest extends ApiTestSupport {

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
        ownerToken = jwtFor(uniqueUserId(), "ORGANIZER");

        ResponseEntity<JsonNode> channelRes = post("/api/v1/channels",
                """
                {"name":"%s","description":"재신청 테스트 채널"}
                """.formatted(uniqueName()), ownerToken);
        long channelId = channelRes.getBody().path("data").path("id").asLong();

        ResponseEntity<JsonNode> expoRes = post("/api/v1/channels/" + channelId + "/expos",
                """
                {"title":"재신청 테스트 박람회","category":"IT·전자","description":"설명","venue":"코엑스","region":"서울"}
                """, ownerToken);
        expoId = expoRes.getBody().path("data").path("id").asLong();

        var expo = expoRepository.findById(expoId).orElseThrow();
        expo.publish();
        expoRepository.save(expo);

        when(pgClient.create(anyString(), anyInt())).thenReturn(new PgCreateResult(true, null));
    }

    @Test
    @DisplayName("재신청 시 이전 PENDING 이 실제로는 결제됐으면 환불하고 취소한다")
    void refundsStalePendingThatWasPaid() {
        PaymentTransaction staleTx = stalePending();
        when(pgClient.inquire(staleTx.getPaymentId())).thenReturn(new PgInquiryResult(
                PgPaymentStatus.PAID, 9_900, "pg-tx-1", "PAID", null, storeId, channelKey));
        when(pgClient.cancel(eq(staleTx.getPaymentId()), anyInt(), any()))
                .thenReturn(new PgCancelResult(true, "SUCCEEDED"));

        ResponseEntity<JsonNode> response = apply();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(promotionRepository.findById(staleTx.getRefId()).orElseThrow().getStatus())
                .isEqualTo(ExpoPromotionStatus.CANCELLED);
        assertThat(paymentTransactionRepository.findByRefId(staleTx.getRefId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("재신청 시 이전 PENDING 의 결제 결과를 모르면(결제창만 닫음) 환불 없이 취소만 한다")
    void cancelsStalePendingWithoutRefundWhenUnknown() {
        PaymentTransaction staleTx = stalePending();
        when(pgClient.inquire(staleTx.getPaymentId())).thenReturn(new PgInquiryResult(
                PgPaymentStatus.NOT_FOUND, null, null, null, null, storeId, channelKey));

        ResponseEntity<JsonNode> response = apply();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(promotionRepository.findById(staleTx.getRefId()).orElseThrow().getStatus())
                .isEqualTo(ExpoPromotionStatus.CANCELLED);
        verify(pgClient, never()).cancel(anyString(), anyInt(), any());
    }

    private ResponseEntity<JsonNode> apply() {
        return post("/api/v1/expo-promotions",
                """
                {"expoId":%d}
                """.formatted(expoId), ownerToken);
    }

    private PaymentTransaction stalePending() {
        ExpoPromotion promotion = promotionRepository.save(ExpoPromotion.create(expoId, 9_900, Clock.systemUTC()));
        return paymentTransactionRepository.save(PaymentTransaction.create(
                promotion.getId(), "BE24-01-STALE" + promotion.getId(), 9_900, Instant.now()));
    }
}
