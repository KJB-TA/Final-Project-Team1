package com.team1.payment;

import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.Webhook;
import io.portone.sdk.server.webhook.WebhookTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

@Service
@Transactional
public class WebhookService {
    private final PortOneWebhookVerifier webhookVerifier;
    private final WebhookEventRepository webhookEventRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentService paymentService;
    private final Clock clock;

    public WebhookService(
            PortOneWebhookVerifier webhookVerifier,
            WebhookEventRepository webhookEventRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentService paymentService,
            Clock clock
    ) {
        this.webhookVerifier = webhookVerifier;
        this.webhookEventRepository = webhookEventRepository;
        this.paymentService = paymentService;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.clock = clock;
    }

    public WebhookProcessResult process(String body, String webhookId,
                                        String webhookSignature, String webhookTimestamp) {

        Webhook webhook;
        try {
            webhook = webhookVerifier.verify(body, webhookId, webhookSignature, webhookTimestamp);
        } catch (WebhookVerificationException e) {
            // SDK 예외를 모듈 예외로 감싼다. 소비 Service 가 PortOne SDK 를 의존하지 않게 하기 위해서다.
            throw new WebhookVerificationFailedException("webhook signature verification failed", e);
        }

        if (!(webhook instanceof WebhookTransaction transaction)) {
            return new WebhookProcessResult(null, PaymentApprovalResult.ignored("결제 관련 웹훅 아님"));
        }

        return processTransaction(webhookId, transaction.getData().getPaymentId(),
                transaction.getClass().getSimpleName());
    }

    // 서명 검증을 통과한 결제 웹훅 처리. 테스트가 SDK 타입 없이 부를 수 있게 분리했다.
    WebhookProcessResult processTransaction(String webhookId, String paymentId, String eventType) {
        Long knownRefId = paymentTransactionRepository.findByPaymentId(paymentId)
                .map(PaymentTransaction::getRefId)
                .orElse(null);

        // RECEIVED 로 남은 건 지난번에 결과를 몰라 503 을 준 웹훅이다. 재전송이면 다시 처리한다.
        Optional<WebhookEvent> existing = webhookEventRepository.findByWebhookId(webhookId);
        if (existing.isPresent() && existing.get().getStatus() != WebhookEventStatus.RECEIVED) {
            return new WebhookProcessResult(knownRefId, PaymentApprovalResult.alreadyProcessed());
        }

        WebhookEvent webhookEvent = existing.orElseGet(() -> webhookEventRepository.save(
                WebhookEvent.receive(webhookId, paymentId, eventType, clock.instant())));

        Optional<PaymentTransaction> paymentTransactionOptional = paymentTransactionRepository.findByPaymentId(paymentId);
        if (paymentTransactionOptional.isEmpty()) {
            return new WebhookProcessResult(null, PaymentApprovalResult.unknown("알 수 없는 paymentId"));
        }
        PaymentTransaction paymentTransaction = paymentTransactionOptional.get();

        PaymentApprovalResult approvalResult = paymentService.confirm(paymentTransaction.getRefId());

        // 결과를 모르면 처리 완료로 적지 않는다. 적으면 PortOne 재전송이 중복으로 무시된다.
        if (approvalResult.outcome() != PaymentApprovalOutcome.UNKNOWN) {
            webhookEvent.markProcessed(clock.instant());
        }

        return new WebhookProcessResult(paymentTransaction.getRefId(), approvalResult);
    }
}
