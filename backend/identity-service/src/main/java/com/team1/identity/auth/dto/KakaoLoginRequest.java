package com.team1.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 카카오는 JS SDK v2 에 팝업 토큰 로그인이 없어 인가 코드(authorization code) 방식을 쓴다.
 * 프론트가 받은 code 와, 그 code 를 받은 redirectUri 를 함께 넘긴다.
 * 서버가 이 둘로 카카오에서 액세스 토큰을 교환하고 사용자 정보를 조회한다.
 */
public record KakaoLoginRequest(
        @NotBlank(message = "카카오 인가 코드는 필수입니다.")
        String code,
        @NotBlank(message = "redirectUri 는 필수입니다.")
        String redirectUri
) {
}
