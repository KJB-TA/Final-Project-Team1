package com.team1.identity.auth;

import com.team1.identity.auth.dto.GoogleUserInfoResponse;
import com.team1.identity.auth.service.GoogleApiClient;
import com.team1.identity.common.exception.BusinessException;
import com.team1.identity.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 구글 토큰의 발급 대상(aud) 검증. 구글 서버 대신 MockRestServiceServer 로 응답을 흉내 낸다.
 */
class GoogleApiClientTest {

    private static final String CLIENT_ID = "our-client.apps.googleusercontent.com";
    private static final String TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo?access_token=token";
    private static final String USER_INFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo";
    private static final String USER_INFO_JSON =
            "{\"id\":\"google-1\",\"email\":\"a@gmail.com\",\"verified_email\":true,\"name\":\"구글\"}";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private static String tokenInfo(String aud) {
        return "{\"aud\":\"" + aud + "\",\"email\":\"a@gmail.com\",\"expires_in\":\"3599\"}";
    }

    @Test
    @DisplayName("우리 Client ID 로 발급된 토큰이면 userinfo 를 조회해 돌려준다")
    void 우리_앱_토큰은_통과한다() {
        server.expect(requestTo(TOKEN_INFO_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(tokenInfo(CLIENT_ID), MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_INFO_URL)).andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(USER_INFO_JSON, MediaType.APPLICATION_JSON));

        GoogleUserInfoResponse info = new GoogleApiClient(builder, CLIENT_ID).getUserInfo("token");

        assertThat(info.id()).isEqualTo("google-1");
        assertThat(info.verifiedEmail()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("다른 앱에 발급된 토큰이면 userinfo 를 조회하지 않고 소셜 로그인 실패로 던진다")
    void 다른_앱_토큰은_거절된다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(tokenInfo("attacker-client.apps.googleusercontent.com"),
                        MediaType.APPLICATION_JSON));
        server.expect(never(), requestTo(USER_INFO_URL));

        assertThatThrownBy(() -> new GoogleApiClient(builder, CLIENT_ID).getUserInfo("token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SOCIAL_LOGIN_FAILED);
        server.verify();
    }

    @Test
    @DisplayName("Client ID 가 설정되지 않았으면 비교 기준이 없으므로 모든 토큰을 거절한다")
    void Client_ID_가_없으면_거절된다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess(tokenInfo(""), MediaType.APPLICATION_JSON));
        server.expect(never(), requestTo(USER_INFO_URL));

        assertThatThrownBy(() -> new GoogleApiClient(builder, "").getUserInfo("token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SOCIAL_LOGIN_FAILED);
    }

    @Test
    @DisplayName("구글이 토큰을 거절하면(4xx) 소셜 로그인 실패, 구글 장애(5xx)면 의존성 장애로 던진다")
    void 구글_응답_오류를_구분한다() {
        server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withBadRequest());
        assertThatThrownBy(() -> new GoogleApiClient(builder, CLIENT_ID).getUserInfo("token"))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SOCIAL_LOGIN_FAILED);

        server.reset();
        server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withServerError());
        assertThatThrownBy(() -> new GoogleApiClient(builder, CLIENT_ID).getUserInfo("token"))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }
}
