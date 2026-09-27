package com.team1.identity.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 네이버 토큰 교환(/oauth2.0/token) 응답에서 액세스 토큰만 받는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverTokenResponse(@JsonProperty("access_token") String accessToken) {
}
