package com.team1.identity.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 구글 userinfo(v2) 응답에서 필요한 필드만 받는다.
 * id 는 구글이 주는 사용자 고유 식별자(문자열)로, provider_id 로 저장한다.
 * 사진 등 우리가 안 쓰는 필드가 와도 무시하도록 ignoreUnknown 을 켠다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleUserInfoResponse(
        String id,
        String email,
        String name
) {
}
