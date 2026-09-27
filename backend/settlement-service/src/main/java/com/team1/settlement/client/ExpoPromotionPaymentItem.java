package com.team1.settlement.client;

import java.time.Instant;

public record ExpoPromotionPaymentItem(
        String paymentId,
        int amount,
        Instant paidAt,
        Instant cancelledAt,
        String status,
        Long expoId
) {
}
