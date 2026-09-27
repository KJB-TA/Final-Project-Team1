package com.team1.identity.auth.service;

import com.team1.identity.auth.dto.GoogleTokenInfoResponse;
import com.team1.identity.auth.dto.GoogleUserInfoResponse;
import com.team1.identity.common.exception.BusinessException;
import com.team1.identity.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 구글 액세스 토큰으로 사용자 정보를 조회한다.
 * 토큰이 잘못됐거나 만료됐으면 구글이 4xx 를 주므로 소셜 로그인 실패로,
 * 구글 장애(5xx·네트워크)면 의존성 장애로 구분해 던진다.
 *
 * <p>userinfo 는 <b>어느 앱이 받은 토큰이든</b> 정상 응답한다. 확인 없이 믿으면 다른 사이트가
 * 받아 간 피해자의 구글 토큰으로 우리 서비스에 피해자로 로그인할 수 있다. 그래서 먼저 tokeninfo 로
 * 토큰의 발급 대상(aud)이 우리 Client ID 인지 확인한다.
 */
@Component
public class GoogleApiClient {

    private static final String GOOGLE_TOKEN_INFO_URL =
            "https://oauth2.googleapis.com/tokeninfo?access_token={token}";
    private static final String GOOGLE_USER_INFO_URL =
            "https://www.googleapis.com/oauth2/v2/userinfo";

    private final RestClient restClient;
    private final String clientId;

    public GoogleApiClient(RestClient.Builder restClientBuilder,
                           @Value("${google.client-id:}") String clientId) {
        this.restClient = restClientBuilder.build();
        this.clientId = clientId;
    }

    public GoogleUserInfoResponse getUserInfo(String googleAccessToken) {
        try {
            verifyAudience(googleAccessToken);
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

    // Client ID 가 설정되지 않았으면 비교할 기준이 없으므로 모든 토큰을 거절한다(fail-closed).
    private void verifyAudience(String googleAccessToken) {
        GoogleTokenInfoResponse info = restClient.get()
                .uri(GOOGLE_TOKEN_INFO_URL, googleAccessToken)
                .retrieve()
                .body(GoogleTokenInfoResponse.class);
        if (clientId == null || clientId.isBlank() || info == null || !clientId.equals(info.aud())) {
            throw new BusinessException(ErrorCode.SOCIAL_LOGIN_FAILED);
        }
    }
}
