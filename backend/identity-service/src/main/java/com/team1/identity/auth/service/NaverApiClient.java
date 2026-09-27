package com.team1.identity.auth.service;

import com.team1.identity.auth.dto.NaverTokenResponse;
import com.team1.identity.auth.dto.NaverUserInfoResponse;
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
 * 네이버 인가 코드를 액세스 토큰으로 바꾼 뒤 회원 정보를 조회한다.
 * 코드·토큰이 잘못됐으면 네이버가 4xx 를 주므로 소셜 로그인 실패로,
 * 네이버 장애(5xx·네트워크)면 의존성 장애로 구분해 던진다.
 */
@Component
public class NaverApiClient {

    private static final String NAVER_TOKEN_URL = "https://nid.naver.com/oauth2.0/token";
    private static final String NAVER_USER_INFO_URL = "https://openapi.naver.com/v1/nid/me";

    private final RestClient restClient = RestClient.create();
    private final String clientId;
    private final String clientSecret;

    public NaverApiClient(@Value("${naver.client-id:}") String clientId,
                          @Value("${naver.client-secret:}") String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** 인가 코드 → 액세스 토큰 → 회원 정보. */
    public NaverUserInfoResponse getUserInfoByCode(String code, String state) {
        String accessToken = exchangeToken(code, state);
        return getUserInfo(accessToken);
    }

    private String exchangeToken(String code, String state) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("state", state);
        try {
            NaverTokenResponse token = restClient.post()
                    .uri(NAVER_TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(NaverTokenResponse.class);
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

    private NaverUserInfoResponse getUserInfo(String naverAccessToken) {
        try {
            return restClient.get()
                    .uri(NAVER_USER_INFO_URL)
                    .header("Authorization", "Bearer " + naverAccessToken)
                    .retrieve()
                    .body(NaverUserInfoResponse.class);
        } catch (HttpClientErrorException e) {
            throw new BusinessException(ErrorCode.SOCIAL_LOGIN_FAILED);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }
}
