package com.team1.identity.auth;

import com.team1.identity.auth.dto.KakaoUserInfoResponse;
import com.team1.identity.auth.dto.LoginResponse;
import com.team1.identity.auth.dto.SignUpRequest;
import com.team1.identity.auth.dto.SignUpResponse;
import com.team1.identity.auth.repository.OauthAccountRepository;
import com.team1.identity.auth.service.AuthService;
import com.team1.identity.auth.service.KakaoApiClient;
import com.team1.identity.common.exception.BusinessException;
import com.team1.identity.common.exception.ErrorCode;
import com.team1.identity.support.IntegrationTestSupport;
import com.team1.identity.user.repository.UserRepository;
import com.team1.security.AuthenticatedUser;
import com.team1.security.JwtValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 카카오 로그인. 카카오 조회는 @MockBean 으로 대신하고, 계정 연결·생성과 토큰 발급을 실제 MySQL 로 검증한다.
 */
class KakaoLoginServiceTest extends IntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OauthAccountRepository oauthAccountRepository;

    @MockBean
    private KakaoApiClient kakaoApiClient;

    @Autowired
    private ClientHttpRequestFactorySettings httpClientSettings;

    private static KakaoUserInfoResponse profile(long id, String email, String nickname) {
        return profile(id, email, true, nickname);
    }

    private static KakaoUserInfoResponse profile(long id, String email, boolean emailVerified, String nickname) {
        return new KakaoUserInfoResponse(id, new KakaoUserInfoResponse.KakaoAccount(
                email, emailVerified, new KakaoUserInfoResponse.Profile(nickname)));
    }

    @Test
    @DisplayName("처음 로그인하는 카카오 계정은 USER 회원으로 새로 만들고, 검증 가능한 토큰을 발급한다")
    void 신규_카카오_계정은_회원으로_생성된다() {
        String email = uniqueEmail();
        when(kakaoApiClient.getUserInfoByCode(anyString(), anyString())).thenReturn(profile(1001L, email, "카카오사용자"));

        LoginResponse response = authService.kakaoLogin("code", "http://localhost:5173/auth/kakao/callback");

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
        when(kakaoApiClient.getUserInfoByCode(anyString(), anyString())).thenReturn(profile(1002L, email, "카카오사용자"));

        LoginResponse response = authService.kakaoLogin("code", "http://localhost:5173/auth/kakao/callback");

        AuthenticatedUser authenticated = new JwtValidator(TEST_JWT_SECRET).validate(response.accessToken());
        assertThat(authenticated.userId()).isEqualTo(existing.userId());
        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    @DisplayName("같은 카카오 계정으로 두 번 로그인해도 회원·연결이 중복 생성되지 않는다")
    void 재로그인은_중복을_만들지_않는다() {
        String email = uniqueEmail();
        when(kakaoApiClient.getUserInfoByCode(anyString(), anyString())).thenReturn(profile(1003L, email, "카카오사용자"));

        LoginResponse first = authService.kakaoLogin("code", "http://localhost:5173/auth/kakao/callback");
        long accountsAfterFirst = oauthAccountRepository.count();
        LoginResponse second = authService.kakaoLogin("code", "http://localhost:5173/auth/kakao/callback");

        AuthenticatedUser u1 = new JwtValidator(TEST_JWT_SECRET).validate(first.accessToken());
        AuthenticatedUser u2 = new JwtValidator(TEST_JWT_SECRET).validate(second.accessToken());
        assertThat(u2.userId()).isEqualTo(u1.userId());
        assertThat(oauthAccountRepository.count()).isEqualTo(accountsAfterFirst);
    }

    @Test
    @DisplayName("카카오가 이메일을 주지 않으면(방안 B) 식별자 기반 placeholder 이메일로 회원을 만든다")
    void 이메일이_없으면_placeholder_로_생성된다() {
        when(kakaoApiClient.getUserInfoByCode(anyString(), anyString())).thenReturn(profile(1004L, null, "카카오사용자"));

        LoginResponse response = authService.kakaoLogin("code", "http://localhost:5173/auth/kakao/callback");

        AuthenticatedUser authenticated = new JwtValidator(TEST_JWT_SECRET).validate(response.accessToken());
        assertThat(authenticated.role()).isEqualTo("USER");
        assertThat(userRepository.findByEmail("kakao_1004@social.expohub.local")).isPresent();
    }

    @Test
    @DisplayName("카카오 토큰이 유효하지 않으면(카카오가 4xx) 소셜 로그인 실패로 던진다")
    void 잘못된_토큰은_실패한다() {
        when(kakaoApiClient.getUserInfoByCode(anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.SOCIAL_LOGIN_FAILED));

        assertThatThrownBy(() -> authService.kakaoLogin("bad-code", "http://localhost:5173/auth/kakao/callback"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SOCIAL_LOGIN_FAILED);
    }

    @Test
    @DisplayName("카카오가 확인하지 않은 이메일이면 같은 이메일 회원에 연결하지 않고 placeholder 로 만든다")
    void 미확인_이메일은_기존_회원에_연결되지_않는다() {
        String email = uniqueEmail();
        SignUpResponse existing = authService.signUp(new SignUpRequest(email, "password123", "기존회원"));
        when(kakaoApiClient.getUserInfoByCode(anyString(), anyString()))
                .thenReturn(profile(1005L, email, false, "공격자"));

        LoginResponse response = authService.kakaoLogin("code", "http://localhost:5173/auth/kakao/callback");

        AuthenticatedUser authenticated = new JwtValidator(TEST_JWT_SECRET).validate(response.accessToken());
        assertThat(authenticated.userId()).isNotEqualTo(existing.userId());
        assertThat(userRepository.findByEmail("kakao_1005@social.expohub.local")).isPresent();
    }

    @Test
    @DisplayName("placeholder 도메인으로는 일반 가입할 수 없어, 카카오 계정의 placeholder 를 미리 선점할 수 없다")
    void placeholder_도메인은_가입이_막힌다() {
        assertThatThrownBy(() -> authService.signUp(
                new SignUpRequest("kakao_1006@social.expohub.local", "password123", "선점시도")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(userRepository.findByEmail("kakao_1006@social.expohub.local")).isEmpty();
    }

    @Test
    @DisplayName("카카오를 호출하는 동안에는 DB 트랜잭션이 없다 - 응답이 느려도 커넥션을 붙잡지 않는다")
    void 외부_호출은_트랜잭션_밖에서_한다() {
        String email = uniqueEmail();
        when(kakaoApiClient.getUserInfoByCode(anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return profile(1007L, email, "카카오사용자");
        });

        authService.kakaoLogin("code", "http://localhost:5173/auth/kakao/callback");

        assertThat(userRepository.findByEmail(email)).isPresent();
    }

    @Test
    @DisplayName("소셜 제공자 호출에는 연결 2초·응답 3초 타임아웃이 걸린다")
    void 소셜_호출_타임아웃이_설정된다() {
        assertThat(httpClientSettings.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(httpClientSettings.readTimeout()).isEqualTo(Duration.ofSeconds(3));
    }
}
