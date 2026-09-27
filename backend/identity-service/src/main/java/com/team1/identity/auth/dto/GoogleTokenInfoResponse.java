package com.team1.identity.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 구글 tokeninfo 응답에서 토큰의 발급 대상(aud)만 받는다.
 * aud 는 이 토큰을 받아 간 OAuth Client ID 로, 우리 Client ID 와 같아야 우리 앱에서 로그인한 토큰이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleTokenInfoResponse(String aud) {
}
