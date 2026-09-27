package com.team1.identity.auth.service;

import com.team1.identity.auth.dto.NaverUserInfoResponse;
import com.team1.identity.common.exception.BusinessException;
import com.team1.identity.common.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 네이버 액세스 토큰으로 회원 프로필을 조회한다.
 * 토큰이 잘못됐거나 만료됐으면 네이버가 4xx 를 주므로 소셜 로그인 실패로,
 * 네이버 장애(5xx·네트워크)면 의존성 장애로 구분해 던진다.
 */
@Component
public class NaverApiClient {

    private static final String NAVER_USER_INFO_URL = "https://openapi.naver.com/v1/nid/me";

    private final RestClient restClient = RestClient.create();

    public NaverUserInfoResponse getUserInfo(String naverAccessToken) {
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
