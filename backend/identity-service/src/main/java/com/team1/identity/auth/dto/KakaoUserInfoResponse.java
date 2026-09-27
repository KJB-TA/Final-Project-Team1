package com.team1.identity.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 회원 정보(/v2/user/me) 응답. 값이 삼단으로 중첩돼 온다.
 * id 는 카카오 고유 식별자(숫자)로 provider_id 로 저장한다.
 * 이메일은 kakao_account.email 에 오며, 동의항목·비즈앱 설정에 따라 없을 수 있다.
 * isEmailVerified 는 카카오가 이메일 소유를 확인했는지다. 확인 안 된 이메일은 남의 주소일 수 있다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoUserInfoResponse(
        Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KakaoAccount(
            String email,
            @JsonProperty("is_email_verified") Boolean isEmailVerified,
            Profile profile
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(String nickname) {
    }
}
