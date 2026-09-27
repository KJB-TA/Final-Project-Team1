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
import com.team1.payment.PgCommunicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// PG 가 환불을 거절하거나 응답이 없을 때. 배너는 바로 내려가고, 결제는 재시도 대상으로 남아야 한다.
class PromotionRefundFailureTest extends ApiTestSupport {

    @MockBean
    private PgClient pgClient;

    @Autowired
    private ExpoPromotionRepository promotionRepository;
    @Autowired
    private ExpoPaymentTransactionRepository paymentTransactionRepository;
    @Autowired
    private ExpoRepository expoRepository;

    private String ownerToken;
    private long expoId;

    @BeforeEach
    void setUp() {
        ownerToken = jwtFor(uniqueUserId(), "ORGANIZER");

        ResponseEntity<JsonNode> channelRes = post("/api/v1/channels",
                """
                {"name":"%s","description":"환불 실패 테스트 채널"}
                """.formatted(uniqueName()), ownerToken);
        long channelId = channelRes.getBody().path("data").path("id").asLong();

        ResponseEntity<JsonNode> expoRes = post("/api/v1/channels/" + channelId + "/expos",
                """
                {"title":"환불 실패 테스트 박람회","category":"IT·전자","description":"설명","venue":"코엑스","region":"서울"}
                """, ownerToken);
        expoId = expoRes.getBody().path("data").path("id").asLong();

        var expo = expoRepository.findById(expoId).orElseThrow();
        expo.publish();
        expoRepository.save(expo);
    }

    @Test
    @DisplayName("PG 가 환불을 거절해도 배너는 CANCELLED 로 내려가고 결제는 REFUND_FAILED 로 재시도를 기다린다")
    void refundRejectedByPg() {
        long promotionId = activePromotion();
        when(pgClient.cancel(anyString(), anyInt(), any()))
                .thenReturn(new PgCancelResult(false, "CANCEL_REJECTED"));

        ResponseEntity<JsonNode> response = post(
                "/api/v1/expo-promotions/" + promotionId + "/refund", "", ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(promotionRepository.findById(promotionId).orElseThrow().getStatus())
                .isEqualTo(ExpoPromotionStatus.CANCELLED);
        PaymentTransaction tx = paymentTransactionRepository.findByRefId(promotionId).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        assertThat(tx.getNextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("PG 통신이 실패해도 롤백되지 않고 REFUND_FAILED 기록이 남아 재시도 대상이 된다")
    void refundRecordSurvivesPgCommunicationFailure() {
        long promotionId = activePromotion();
        when(pgClient.cancel(anyString(), anyInt(), any()))
                .thenThrow(new PgCommunicationException("timeout"));

        ResponseEntity<JsonNode> response = post(
                "/api/v1/expo-promotions/" + promotionId + "/refund", "", ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(promotionRepository.findById(promotionId).orElseThrow().getStatus())
                .isEqualTo(ExpoPromotionStatus.CANCELLED);
        PaymentTransaction tx = paymentTransactionRepository.findByRefId(promotionId).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        assertThat(tx.getNextAttemptAt()).isNotNull();
    }

    private long activePromotion() {
        ExpoPromotion promotion = ExpoPromotion.create(expoId, 9_900, Clock.systemUTC());
        promotion.confirm(Clock.systemUTC());
        promotionRepository.save(promotion);

        PaymentTransaction tx = PaymentTransaction.create(
                promotion.getId(), "BE24-01-TEST" + promotion.getId(), 9_900, Instant.now());
        tx.markPaid("pg-tx-" + promotion.getId(), "OK", Instant.now());
        paymentTransactionRepository.save(tx);

        return promotion.getId();
    }
}
