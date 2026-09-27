package com.team1.identity.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 네이버 회원 프로필(/v1/nid/me) 응답. 실제 값은 response 안에 중첩돼 온다.
 * response.id 는 네이버 고유 식별자로 provider_id 로 저장하고, email 로 회원을 잇는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverUserInfoResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(String id, String email, String name) {
    }
}
