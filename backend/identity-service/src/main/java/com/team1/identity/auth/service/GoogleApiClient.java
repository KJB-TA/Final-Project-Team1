package com.team1.identity.auth.service;

import com.team1.identity.auth.dto.GoogleUserInfoResponse;
import com.team1.identity.common.exception.BusinessException;
import com.team1.identity.common.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 구글 액세스 토큰으로 사용자 정보를 조회한다.
 * 토큰이 잘못됐거나 만료됐으면 구글이 4xx 를 주므로 소셜 로그인 실패로,
 * 구글 장애(5xx·네트워크)면 의존성 장애로 구분해 던진다.
 */
@Component
public class GoogleApiClient {

    private static final String GOOGLE_USER_INFO_URL =
            "https://www.googleapis.com/oauth2/v2/userinfo";

    private final RestClient restClient = RestClient.create();

    public GoogleUserInfoResponse getUserInfo(String googleAccessToken) {
        try {
            return restClient.get()
                    .uri(GOOGLE_USER_INFO_URL)
                    .header("Authorization", "Bearer " + googleAccessToken)
                    .retrieve()
                    .body(GoogleUserInfoResponse.class);
        } catch (HttpClientErrorException e) {
            // 잘못되거나 만료된 토큰
            throw new BusinessException(ErrorCode.SOCIAL_LOGIN_FAILED);
        } catch (RestClientException e) {
            // 구글 장애·네트워크 오류
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }
}
