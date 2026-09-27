package com.team1.expo.promotion.controller;

import com.team1.expo.common.TraceId;
import com.team1.expo.common.exception.BusinessException;
import com.team1.expo.common.exception.ErrorCode;
import com.team1.expo.common.response.ApiResponse;
import com.team1.expo.promotion.dto.ActivePromotionResponse;
import com.team1.expo.promotion.dto.ApplyPromotionRequest;
import com.team1.expo.promotion.dto.ApplyPromotionResponse;
import com.team1.expo.promotion.dto.PromotionPaymentResponse;
import com.team1.expo.promotion.service.ExpoPromotionService;
import com.team1.payment.WebhookProcessResult;
import com.team1.payment.WebhookService;
import com.team1.payment.WebhookVerificationFailedException;
import com.team1.security.AuthContext;
import com.team1.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/expo-promotions")
@RequiredArgsConstructor
public class ExpoPromotionController {

    private static final Logger log = LoggerFactory.getLogger(ExpoPromotionController.class);

    private final ExpoPromotionService promotionService;
    private final WebhookService webhookService;

    @GetMapping("/active")
    public ApiResponse<List<ActivePromotionResponse>> getActive() {
        return ApiResponse.ok(promotionService.getActive());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApplyPromotionResponse> apply(@Valid @RequestBody ApplyPromotionRequest request) {
        AuthenticatedUser user = requireOrganizer();
        return ApiResponse.ok(promotionService.apply(user.userId(), request));
    }

    // 결제창이 닫힌 뒤 화면이 부른다. 서버가 PG 에 직접 확인한다 - 예약의 POST /reservations/{id}/payment 와 같은 역할
    @PostMapping("/{promotionId}/payment")
    public ApiResponse<PromotionPaymentResponse> confirmPayment(@PathVariable Long promotionId) {
        AuthenticatedUser user = requireOrganizer();
        return ApiResponse.ok(promotionService.confirmPayment(user.userId(), promotionId));
    }

    @PostMapping("/{promotionId}/refund")
    public ApiResponse<Void> refund(@PathVariable Long promotionId) {
        AuthenticatedUser user = requireOrganizer();
        promotionService.refund(user.userId(), promotionId);
        return ApiResponse.ok(null);
    }

    // PortOne 웹훅. JWT 대신 Webhook Secret 서명으로 보낸 쪽을 확인한다
    @PostMapping("/webhooks/portone")
    public ResponseEntity<ApiResponse<?>> portoneWebhook(
            @RequestBody String body,
            @RequestHeader("webhook-id") String webhookId,
            @RequestHeader("webhook-signature") String webhookSignature,
            @RequestHeader("webhook-timestamp") String webhookTimestamp) {

        WebhookProcessResult processed;
        try {
            processed = webhookService.process(body, webhookId, webhookSignature, webhookTimestamp);
        } catch (WebhookVerificationFailedException e) {
            log.warn("promotion webhook signature verification failed webhookId={} traceId={}", webhookId, TraceId.get());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(ErrorCode.UNAUTHENTICATED.name(), "invalid webhook signature"));
        }

        // 결과를 모르면 503 으로 PortOne 재전송을 받는다
        return promotionService.applyWebhook(processed)
                ? ResponseEntity.ok(ApiResponse.ok(null))
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error(ErrorCode.DEPENDENCY_UNAVAILABLE.name(), "payment result unknown, retry expected"));
    }

    private AuthenticatedUser requireOrganizer() {
        AuthenticatedUser user = AuthContext.get();
        if (user == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if (!"ORGANIZER".equals(user.role())) throw new BusinessException(ErrorCode.FORBIDDEN);
        return user;
    }
}
