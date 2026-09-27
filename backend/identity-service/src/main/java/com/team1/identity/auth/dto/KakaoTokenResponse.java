package com.team1.identity.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 카카오 토큰 교환(/oauth/token) 응답에서 액세스 토큰만 받는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoTokenResponse(@JsonProperty("access_token") String accessToken) {
}
