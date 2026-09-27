package com.team1.identity.auth;

import com.team1.identity.auth.dto.LoginResponse;
import com.team1.identity.auth.dto.NaverUserInfoResponse;
import com.team1.identity.auth.dto.SignUpRequest;
import com.team1.identity.auth.dto.SignUpResponse;
import com.team1.identity.auth.repository.OauthAccountRepository;
import com.team1.identity.auth.service.AuthService;
import com.team1.identity.auth.service.NaverApiClient;
import com.team1.identity.common.exception.BusinessException;
import com.team1.identity.common.exception.ErrorCode;
import com.team1.identity.support.IntegrationTestSupport;
import com.team1.identity.user.repository.UserRepository;
import com.team1.security.AuthenticatedUser;
import com.team1.security.JwtValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 네이버 로그인. 네이버 조회는 @MockBean 으로 대신하고, 계정 연결·생성과 토큰 발급을 실제 MySQL 로 검증한다.
 */
class NaverLoginServiceTest extends IntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OauthAccountRepository oauthAccountRepository;

    @MockBean
    private NaverApiClient naverApiClient;

    private static NaverUserInfoResponse profile(String id, String email, String name) {
        return new NaverUserInfoResponse(new NaverUserInfoResponse.Response(id, email, name));
    }

    @Test
    @DisplayName("처음 로그인하는 네이버 계정은 USER 회원으로 새로 만들고, 검증 가능한 토큰을 발급한다")
    void 신규_네이버_계정은_회원으로_생성된다() {
        String email = uniqueEmail();
        when(naverApiClient.getUserInfo(anyString())).thenReturn(profile("naver-1", email, "네이버사용자"));

        LoginResponse response = authService.naverLogin("access-token");

        AuthenticatedUser authenticated = new JwtValidator(TEST_JWT_SECRET).validate(response.accessToken());
        assertThat(authenticated.role()).isEqualTo("USER");
        assertThat(userRepository.findByEmail(email)).isPresent();
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("같은 이메일로 이미 가입한 회원이 있으면 새로 만들지 않고 그 회원에 연결한다")
    void 같은_이메일이면_기존_회원에_연결된다() {
        String email = uniqueEmail();
        SignUpResponse existing = authService.signUp(new SignUpRequest(email, "password123", "기존회원"));
        long usersBefore = userRepository.count();
        when(naverApiClient.getUserInfo(anyString())).thenReturn(profile("naver-2", email, "네이버사용자"));

        LoginResponse response = authService.naverLogin("access-token");

        AuthenticatedUser authenticated = new JwtValidator(TEST_JWT_SECRET).validate(response.accessToken());
        assertThat(authenticated.userId()).isEqualTo(existing.userId());
        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    @DisplayName("같은 네이버 계정으로 두 번 로그인해도 회원·연결이 중복 생성되지 않는다")
    void 재로그인은_중복을_만들지_않는다() {
        String email = uniqueEmail();
        when(naverApiClient.getUserInfo(anyString())).thenReturn(profile("naver-3", email, "네이버사용자"));

        LoginResponse first = authService.naverLogin("access-token");
        long accountsAfterFirst = oauthAccountRepository.count();
        LoginResponse second = authService.naverLogin("access-token");

        AuthenticatedUser u1 = new JwtValidator(TEST_JWT_SECRET).validate(first.accessToken());
        AuthenticatedUser u2 = new JwtValidator(TEST_JWT_SECRET).validate(second.accessToken());
        assertThat(u2.userId()).isEqualTo(u1.userId());
        assertThat(oauthAccountRepository.count()).isEqualTo(accountsAfterFirst);
    }

    @Test
    @DisplayName("네이버가 이메일을 주지 않으면(방안 A) 소셜 로그인 실패로 던진다")
    void 이메일이_없으면_실패한다() {
        when(naverApiClient.getUserInfo(anyString())).thenReturn(profile("naver-4", null, "네이버사용자"));

        assertThatThrownBy(() -> authService.naverLogin("access-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SOCIAL_LOGIN_FAILED);
    }

    @Test
    @DisplayName("네이버 토큰이 유효하지 않으면(네이버가 4xx) 소셜 로그인 실패로 던진다")
    void 잘못된_토큰은_실패한다() {
        when(naverApiClient.getUserInfo(anyString()))
                .thenThrow(new BusinessException(ErrorCode.SOCIAL_LOGIN_FAILED));

        assertThatThrownBy(() -> authService.naverLogin("bad-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SOCIAL_LOGIN_FAILED);
    }
}
