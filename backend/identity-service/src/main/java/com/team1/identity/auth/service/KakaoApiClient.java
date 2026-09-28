package com.team1.identity.auth.service;

import com.team1.identity.auth.dto.KakaoTokenResponse;
import com.team1.identity.auth.dto.KakaoUserInfoResponse;
import com.team1.identity.common.exception.BusinessException;
import com.team1.identity.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 인가 코드를 액세스 토큰으로 바꾼 뒤 회원 정보를 조회한다.
 * 코드·토큰이 잘못됐으면 카카오가 4xx 를 주므로 소셜 로그인 실패로,
 * 카카오 장애(5xx·네트워크)면 의존성 장애로 구분해 던진다.
 */
@Component
public class KakaoApiClient {

    private static final String KAKAO_TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String KAKAO_USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";

    private final RestClient restClient;
    private final String restApiKey;
    private final String clientSecret;

    // Builder 는 spring.http.client.* 의 연결·응답 타임아웃이 적용된 것을 받는다. 없으면 무한 대기다.
    public KakaoApiClient(RestClient.Builder restClientBuilder,
                          @Value("${kakao.rest-api-key:}") String restApiKey,
                          @Value("${kakao.client-secret:}") String clientSecret) {
        this.restClient = restClientBuilder.build();
        this.restApiKey = restApiKey;
        this.clientSecret = clientSecret;
    }

    /** 인가 코드 → 액세스 토큰 → 회원 정보. */
    public KakaoUserInfoResponse getUserInfoByCode(String code, String redirectUri) {
        String accessToken = exchangeToken(code, redirectUri);
        return getUserInfo(accessToken);
    }

    private String exchangeToken(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", restApiKey);
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        // 카카오는 새 앱의 REST 키에 클라이언트 시크릿이 기본 활성화돼 있어, 켜져 있으면 함께 보내야 한다.
        if (clientSecret != null && !clientSecret.isBlank()) {
            form.add("client_secret", clientSecret);
        }
        try {
            KakaoTokenResponse token = restClient.post()
                    .uri(KAKAO_TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KakaoTokenResponse.class);
            if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
                throw new BusinessException(ErrorCode.SOCIAL_LOGIN_FAILED);
            }
            return token.accessToken();
        } catch (HttpClientErrorException e) {
            throw new BusinessException(ErrorCode.SOCIAL_LOGIN_FAILED);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private KakaoUserInfoResponse getUserInfo(String kakaoAccessToken) {
        try {
            return restClient.get()
                    .uri(KAKAO_USER_INFO_URL)
                    .header("Authorization", "Bearer " + kakaoAccessToken)
                    .retrieve()
                    .body(KakaoUserInfoResponse.class);
        } catch (HttpClientErrorException e) {
            throw new BusinessException(ErrorCode.SOCIAL_LOGIN_FAILED);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }
}
