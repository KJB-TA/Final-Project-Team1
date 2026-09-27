package com.team1.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 프론트가 구글 로그인으로 받은 액세스 토큰을 그대로 넘긴다.
 * 서버는 이 토큰으로 구글 userinfo 를 조회해 신원을 확인한다.
 */
public record GoogleLoginRequest(
        @NotBlank(message = "구글 액세스 토큰은 필수입니다.")
        String googleAccessToken
) {
}
