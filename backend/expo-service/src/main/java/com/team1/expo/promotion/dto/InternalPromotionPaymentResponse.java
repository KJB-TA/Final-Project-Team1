package com.team1.expo.promotion.dto;

import com.team1.payment.PaymentTransaction;

import java.time.Instant;

public record InternalPromotionPaymentResponse(
        String paymentId,
        int amount,
        Instant paidAt,
        Instant cancelledAt,
        String status,
        Long expoId
) {
    public static InternalPromotionPaymentResponse of(PaymentTransaction tx, Long expoId) {
        return new InternalPromotionPaymentResponse(
                tx.getPaymentId(),
                tx.getAmount(),
                tx.getPaidAt(),
                tx.getCancelledAt(),
                tx.getStatus().name(),
                expoId
        );
    }
}
