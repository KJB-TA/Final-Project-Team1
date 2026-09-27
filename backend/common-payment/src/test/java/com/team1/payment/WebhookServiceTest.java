package com.team1.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 결제 결과를 모를 때 웹훅을 처리 완료로 적지 않아, PortOne 재전송이 실제로 다시 처리되는지 본다. */
@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    private static final String WEBHOOK_ID = "wh-1";
    private static final String PAYMENT_ID = "BE24-01-abc";

    @Mock
    private PortOneWebhookVerifier verifier;
    @Mock
    private WebhookEventRepository webhookEvents;
    @Mock
    private PaymentTransactionRepository payments;
    @Mock
    private PaymentService paymentService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC);
    private WebhookService service;

    @BeforeEach
    void setUp() {
        service = new WebhookService(verifier, webhookEvents, payments, paymentService, clock);
        PaymentTransaction tx = PaymentTransaction.create(7L, PAYMENT_ID, 10000, clock.instant());
        when(payments.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(tx));
    }

    @Test
    @DisplayName("결과를 모르면 RECEIVED 로 남긴다 - 처리 완료로 적지 않는다")
    void unknownLeavesEventReceived() {
        when(webhookEvents.findByWebhookId(WEBHOOK_ID)).thenReturn(Optional.empty());
        when(webhookEvents.save(any())).thenAnswer(call -> call.getArgument(0));
        when(paymentService.confirm(7L)).thenReturn(PaymentApprovalResult.unknown("PG 무응답"));

        WebhookProcessResult result = service.processTransaction(WEBHOOK_ID, PAYMENT_ID, "Paid");

        assertThat(result.approvalResult().outcome()).isEqualTo(PaymentApprovalOutcome.UNKNOWN);
        verify(webhookEvents).save(org.mockito.ArgumentMatchers.argThat(
                e -> e.getStatus() == WebhookEventStatus.RECEIVED));
    }

    @Test
    @DisplayName("RECEIVED 로 남은 웹훅이 재전송되면 다시 결제를 확인하고, 이번엔 처리 완료로 적는다")
    void retryOfReceivedEventIsProcessed() {
        WebhookEvent leftOver = WebhookEvent.receive(WEBHOOK_ID, PAYMENT_ID, "Paid", clock.instant());
        when(webhookEvents.findByWebhookId(WEBHOOK_ID)).thenReturn(Optional.of(leftOver));
        when(paymentService.confirm(7L)).thenReturn(PaymentApprovalResult.success(10000));

        WebhookProcessResult result = service.processTransaction(WEBHOOK_ID, PAYMENT_ID, "Paid");

        assertThat(result.approvalResult().outcome()).isEqualTo(PaymentApprovalOutcome.SUCCESS);
        assertThat(leftOver.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        verify(webhookEvents, never()).save(any());   // 같은 webhook_id 로 새 행을 만들지 않는다
    }

    @Test
    @DisplayName("이미 처리 완료된 웹훅은 결제를 다시 확인하지 않는다")
    void processedEventIsDuplicate() {
        WebhookEvent done = WebhookEvent.receive(WEBHOOK_ID, PAYMENT_ID, "Paid", clock.instant());
        done.markProcessed(clock.instant());
        when(webhookEvents.findByWebhookId(WEBHOOK_ID)).thenReturn(Optional.of(done));

        WebhookProcessResult result = service.processTransaction(WEBHOOK_ID, PAYMENT_ID, "Paid");

        assertThat(result.approvalResult().outcome()).isEqualTo(PaymentApprovalOutcome.ALREADY_PROCESSED);
        verify(paymentService, never()).confirm(any());
    }
}
