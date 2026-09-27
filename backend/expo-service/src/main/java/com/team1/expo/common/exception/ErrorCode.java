package com.team1.expo.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    DUPLICATE_CHANNEL_NAME(HttpStatus.CONFLICT, "이미 사용 중인 채널명입니다."),
    CHANNEL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 채널을 보유하고 있습니다."),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "허용되지 않는 상태 변경입니다."),
    PROMOTION_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 진행 중인 VIP 배너가 있습니다."),
    PROMOTION_SLOT_FULL(HttpStatus.CONFLICT, "VIP 배너 슬롯이 모두 사용 중입니다."),
    PROMOTION_EXPO_NOT_PUBLISHED(HttpStatus.CONFLICT, "공개 중인 박람회만 VIP 배너를 신청할 수 있습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 일치하지 않습니다."),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 처리할 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
