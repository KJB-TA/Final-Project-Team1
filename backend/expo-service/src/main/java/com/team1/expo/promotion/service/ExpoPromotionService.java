package com.team1.expo.promotion.service;

import com.team1.expo.common.exception.BusinessException;
import com.team1.expo.common.exception.ErrorCode;
import com.team1.expo.domain.channel.ChannelRepository;
import com.team1.expo.domain.expo.Expo;
import com.team1.expo.domain.expo.ExpoRepository;
import com.team1.expo.domain.expo.ExpoStatus;
import com.team1.expo.domain.promotion.*;
import com.team1.expo.promotion.dto.ActivePromotionResponse;
import com.team1.expo.promotion.dto.ApplyPromotionRequest;
import com.team1.expo.promotion.dto.ApplyPromotionResponse;
import com.team1.expo.promotion.dto.InternalPromotionPaymentResponse;
import com.team1.expo.promotion.dto.PromotionPaymentResponse;
import com.team1.payment.PaymentApprovalOutcome;
import com.team1.payment.PaymentApprovalResult;
import com.team1.payment.PaymentService;
import com.team1.payment.PaymentTransaction;
import com.team1.payment.PgCancelResult;
import com.team1.payment.PgClient;
import com.team1.payment.PgCommunicationException;
import com.team1.payment.WebhookProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ExpoPromotionService {

    private static final Logger log = LoggerFactory.getLogger(ExpoPromotionService.class);
    static final int BANNER_PRICE = 9_900;

    private final ExpoPromotionRepository promotionRepository;
    private final ExpoPaymentTransactionRepository paymentTransactionRepository;
    private final ExpoRepository expoRepository;
    private final ChannelRepository channelRepository;
    private final PgClient pgClient;
    private final PaymentService paymentService;
    private final Clock clock;
    private final int refundMaxAttempts;
    private final Duration refundBackoff;
    private final int bannerMaxSlots;
    private final int bannerDurationDays;

    public ExpoPromotionService(
            ExpoPromotionRepository promotionRepository,
            ExpoPaymentTransactionRepository paymentTransactionRepository,
            ExpoRepository expoRepository,
            ChannelRepository channelRepository,
            PgClient pgClient,
            PaymentService paymentService,
            Clock clock,
            @Value("${scheduler.refund-retry.max-attempts}") int refundMaxAttempts,
            @Value("${scheduler.refund-retry.backoff}") Duration refundBackoff,
            @Value("${banner.max-slots}") int bannerMaxSlots,
            @Value("${banner.duration-days}") int bannerDurationDays
    ){
        this.promotionRepository = promotionRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.expoRepository = expoRepository;
        this.channelRepository = channelRepository;
        this.pgClient = pgClient;
        this.paymentService = paymentService;
        this.clock = clock;
        this.refundMaxAttempts = refundMaxAttempts;
        this.refundBackoff = refundBackoff;
        this.bannerMaxSlots = bannerMaxSlots;
        this.bannerDurationDays = bannerDurationDays;
    }

    @Transactional
    public ApplyPromotionResponse apply(Long requesterId, ApplyPromotionRequest request) {
        verifyOwnership(request.expoId(), requesterId);

        // 방문자에게 보이지 않는 박람회에 노출 비용을 받지 않는다
        if (expoRepository.findById(request.expoId()).map(Expo::getStatus).orElse(null) != ExpoStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.PROMOTION_EXPO_NOT_PUBLISHED);
        }

        // 전체 슬롯 수 초과 시 거절
        if (promotionRepository.countByStatus(ExpoPromotionStatus.ACTIVE) >= bannerMaxSlots) {
            throw new BusinessException(ErrorCode.PROMOTION_SLOT_FULL);
        }

        // ACTIVE면 환불 먼저 해야 함
        if (promotionRepository.existsByExpoIdAndStatusIn(
                request.expoId(), List.of(ExpoPromotionStatus.ACTIVE))) {
            throw new BusinessException(ErrorCode.PROMOTION_ALREADY_EXISTS);
        }
        // 결제 취소 등으로 남은 PENDING은 자동 정리
        promotionRepository.findByExpoIdAndStatus(request.expoId(), ExpoPromotionStatus.PENDING)
                .ifPresent(this::cancelStalePending);

        ExpoPromotion promotion = promotionRepository.save(
                ExpoPromotion.create(request.expoId(), BANNER_PRICE, clock));

        // PG 에 금액을 사전 등록해 두어야 결제 확인 때 금액을 검증할 수 있다(예약 결제와 같은 경로)
        PaymentTransaction tx;
        try {
            tx = paymentService.createPending(promotion.getId(), BANNER_PRICE);
        } catch (PgCommunicationException e) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }

        return new ApplyPromotionResponse(
                promotion.getId(),
                promotion.getExpoId(),
                promotion.getAmount(),
                tx.getPaymentId(),
                promotion.getStatus().name()
        );
    }

    /** 결제창이 닫힌 뒤 주최자 화면이 부른다. PG 에 직접 확인한 결과로만 배너를 켠다. */
    @Transactional
    public PromotionPaymentResponse confirmPayment(Long requesterId, Long promotionId) {
        ExpoPromotion promotion = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        verifyOwnership(promotion.getExpoId(), requesterId);

        if (promotion.getStatus() != ExpoPromotionStatus.ACTIVE) {
            applyPaymentResult(promotion, paymentService.confirm(promotionId));
        }
        return new PromotionPaymentResponse(promotion.getId(), promotion.getStatus().name());
    }

    /** PortOne 웹훅(서명 검증 완료) 결과를 반영한다. 재전송이 필요하면 false. */
    @Transactional
    public boolean applyWebhook(WebhookProcessResult processed) {
        PaymentApprovalOutcome outcome = processed.approvalResult().outcome();
        if (processed.refId() == null
                || outcome == PaymentApprovalOutcome.ALREADY_PROCESSED
                || outcome == PaymentApprovalOutcome.IGNORED) {
            return true;
        }
        if (outcome == PaymentApprovalOutcome.UNKNOWN) {
            return false;
        }
        ExpoPromotion promotion = promotionRepository.findById(processed.refId()).orElse(null);
        if (promotion == null || promotion.getStatus() == ExpoPromotionStatus.ACTIVE) {
            return true;
        }
        try {
            applyPaymentResult(promotion, processed.approvalResult());
        } catch (BusinessException e) {
            // 이미 최종 상태이거나 금액 불일치 - 재전송으로 풀리지 않는다
            log.warn("promotion webhook not applied promotionId={} code={}", promotion.getId(), e.getErrorCode());
        }
        return true;
    }

    // 결제는 끝났는데 웹훅이 아직 안 온 PENDING 일 수 있다. 그냥 취소하면 뒤늦은 웹훅이 이미 취소된
    // 배너라 반영되지 않아 돈만 남는다. PG 를 먼저 조회해 결제됐으면 환불하고 내린다.
    // 모름(결제창만 열고 닫은 경우도 여기다)이면 지금처럼 취소만 한다 - 막으면 재신청이 전부 실패한다.
    private void cancelStalePending(ExpoPromotion stale) {
        if (paymentTransactionRepository.findByRefId(stale.getId()).isPresent()) {
            PaymentApprovalResult result = paymentService.confirm(stale.getId());
            if (result.outcome() == PaymentApprovalOutcome.SUCCESS) {
                log.warn("stale pending promotion was paid, refunding promotionId={}", stale.getId());
                paymentService.cancel(stale.getId(), "배너 재신청으로 이전 결제 환불");
            }
        }
        stale.cancel(clock);
    }

    // 결제 확인 경로(주최자 화면·웹훅)가 모두 이 함수로 상태를 바꾼다
    private void applyPaymentResult(ExpoPromotion promotion, PaymentApprovalResult result) {
        if (promotion.getStatus() != ExpoPromotionStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        switch (result.outcome()) {
            case SUCCESS -> promotion.confirm(clock);
            case FAILED_CONFIRMED -> promotion.cancel(clock);
            case AMOUNT_MISMATCH -> {
                log.warn("promotion payment amount mismatch promotionId={}", promotion.getId());
                throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
            }
            case UNKNOWN -> throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            default -> { }
        }
    }

    @Transactional
    public void refund(Long requesterId, Long promotionId) {
        ExpoPromotion promotion = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        verifyOwnership(promotion.getExpoId(), requesterId);

        if (promotion.getStatus() != ExpoPromotionStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }

        PaymentTransaction tx = paymentTransactionRepository.findByRefId(promotionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        // 배너는 환불 결과와 무관하게 먼저 내린다(예약 취소와 같은 순서).
        // 재시도 배치는 결제 행만 CANCELLED 로 바꾸므로, 여기서 내리지 않으면 돈을 돌려준 뒤에도 노출된다.
        promotion.cancel(clock);

        // 실패해도 예외를 던지지 않는다 - 롤백되면 REFUND_FAILED 기록이 사라져 재시도 대상에서 빠진다.
        try {
            PgCancelResult result = pgClient.cancel(tx.getPaymentId(), tx.getAmount(), "배너 환불");
            if (result.success()) {
                tx.markCancelled(clock.instant());
            } else {
                tx.markRefundFailed("PG 환불 거절 code=" + result.responseCode(), refundMaxAttempts, refundBackoff, clock.instant());
            }
        } catch (PgCommunicationException e) {
            tx.markRefundFailed("PG 통신 실패: " + e.getMessage(), refundMaxAttempts, refundBackoff, clock.instant());
        }
    }

    @Transactional(readOnly = true)
    public List<ActivePromotionResponse> getActive() {
        List<ExpoPromotion> active = promotionRepository.findByStatusOrderByPaidAtAsc(ExpoPromotionStatus.ACTIVE);
        if (active.isEmpty()) return List.of();

        // 숨김·마감 박람회는 자정 만료 배치를 기다리지 않고 바로 뺀다
        Map<Long, Expo> published = expoRepository
                .findAllById(active.stream().map(ExpoPromotion::getExpoId).collect(Collectors.toSet()))
                .stream()
                .filter(e -> e.getStatus() == ExpoStatus.PUBLISHED)
                .collect(Collectors.toMap(Expo::getId, e -> e));
        List<ExpoPromotion> promotions = active.stream()
                .filter(p -> published.containsKey(p.getExpoId()))
                .toList();
        if (promotions.isEmpty()) return List.of();

        // ponytail: 30초 단위 순환 — 상태 없이 시계로만 회전. 수십 개 초과 시 DB 기반 커서로 교체
        int size = promotions.size();
        int offset = (int) ((clock.instant().getEpochSecond() / 30) % size);
        List<ExpoPromotion> rotated = new ArrayList<>(promotions.subList(offset, size));
        rotated.addAll(promotions.subList(0, offset));

        return rotated.stream()
                .map(p -> ActivePromotionResponse.of(p, published.get(p.getExpoId())))
                .toList();
    }

    /** 계약 2 — Settlement-Service가 정산 집계에 사용. 결제·환불 시각이 구간에 든 건을 반환한다. */
    @Transactional(readOnly = true)
    public List<InternalPromotionPaymentResponse> getPaymentsForSettlement(Instant from, Instant to) {
        List<PaymentTransaction> txs = paymentTransactionRepository.findSettlementEvents(from, to);

        Set<Long> promotionIds = txs.stream().map(PaymentTransaction::getRefId).collect(Collectors.toSet());
        Map<Long, Long> promotionToExpoId = promotionRepository.findAllById(promotionIds).stream()
                .collect(Collectors.toMap(ExpoPromotion::getId, ExpoPromotion::getExpoId));

        return txs.stream()
                .map(tx -> InternalPromotionPaymentResponse.of(tx, promotionToExpoId.get(tx.getRefId())))
                .toList();
    }

    /** 매일 자정 — 30일 경과 또는 박람회 종료된 ACTIVE 배너를 EXPIRED로 전환 */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void expirePromotions() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minusDays(bannerDurationDays);

        // 조건 1: 결제일로부터 30일 초과
        List<ExpoPromotion> byDuration = promotionRepository.findByStatusAndPaidAtBefore(
                ExpoPromotionStatus.ACTIVE, cutoff);

        // 조건 2: 박람회가 CLOSED 상태인 경우
        Set<Long> expiredByDurationIds = byDuration.stream()
                .map(ExpoPromotion::getId).collect(Collectors.toSet());

        List<ExpoPromotion> allActive = promotionRepository.findByStatusOrderByPaidAtAsc(ExpoPromotionStatus.ACTIVE);
        Set<Long> closedExpoIds = expoRepository
                .findAllById(allActive.stream().map(ExpoPromotion::getExpoId).collect(Collectors.toSet()))
                .stream()
                .filter(e -> e.getStatus() == ExpoStatus.CLOSED)
                .map(Expo::getId)
                .collect(Collectors.toSet());

        List<ExpoPromotion> toExpire = allActive.stream()
                .filter(p -> expiredByDurationIds.contains(p.getId()) || closedExpoIds.contains(p.getExpoId()))
                .toList();

        toExpire.forEach(p -> p.expire(clock));
        if (!toExpire.isEmpty()) {
            log.info("expired {} promotions (duration or expo closed)", toExpire.size());
        }
    }

    private void verifyOwnership(Long expoId, Long requesterId) {
        Long ownerId = expoRepository.findById(expoId)
                .flatMap(expo -> channelRepository.findById(expo.getChannelId()))
                .map(ch -> ch.getOwnerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        if (!ownerId.equals(requesterId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
