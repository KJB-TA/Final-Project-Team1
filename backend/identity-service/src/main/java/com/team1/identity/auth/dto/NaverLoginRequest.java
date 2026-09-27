package com.team1.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 네이버 로그인(인가 코드 방식). 프론트가 받은 code 와 CSRF 방지용 state 를 넘긴다.
 * 서버가 이 둘로 네이버에서 액세스 토큰을 교환하고 사용자 정보를 조회한다.
 * (SDK 토큰 방식은 매번 계정 확인을 강제할 수 없어 코드 방식으로 통일했다)
 */
public record NaverLoginRequest(
        @NotBlank(message = "네이버 인가 코드는 필수입니다.")
        String code,
        @NotBlank(message = "state 는 필수입니다.")
        String state
) {
}
