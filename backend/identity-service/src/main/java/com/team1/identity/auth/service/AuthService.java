package com.team1.identity.auth.service;

import com.team1.identity.auth.dto.GoogleUserInfoResponse;
import com.team1.identity.auth.dto.KakaoUserInfoResponse;
import com.team1.identity.auth.dto.NaverUserInfoResponse;
import com.team1.identity.auth.dto.LoginRequest;
import com.team1.identity.auth.dto.LoginResponse;
import com.team1.identity.auth.dto.SignUpRequest;
import com.team1.identity.auth.dto.SignUpResponse;
import com.team1.identity.auth.entity.OauthAccount;
import com.team1.identity.auth.jwt.IssuedToken;
import com.team1.identity.auth.jwt.JwtTokenProvider;
import com.team1.identity.auth.repository.OauthAccountRepository;
import com.team1.identity.common.exception.BusinessException;
import com.team1.identity.common.exception.ErrorCode;
import com.team1.identity.common.util.EmailNormalizer;
import com.team1.identity.user.entity.Role;
import com.team1.identity.user.entity.User;
import com.team1.identity.user.repository.UserRepository;
import com.team1.identity.user.service.UserRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    /*
     * 존재하지 않는 이메일로 로그인해도 Hash 비교를 한 번은 수행하기 위한 더미 Hash다.
     * 실제 사용자의 Hash와 같은 work factor(12)여야 걸리는 시간이 같아진다.
     * 이 Hash에 대응하는 계정은 없으므로 이 값으로 로그인할 수 있는 사용자는 존재하지 않는다.
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$12$xtlew4uuJuhTgLn55.5hl.7WZcZ1FZ8xHglmBrAICjFRun3ZJLKWu";

    private static final String PROVIDER_GOOGLE = "GOOGLE";
    private static final String PROVIDER_NAVER = "NAVER";
    private static final String PROVIDER_KAKAO = "KAKAO";

    private final UserRegistrationService userRegistrationService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleApiClient googleApiClient;
    private final NaverApiClient naverApiClient;
    private final KakaoApiClient kakaoApiClient;
    private final OauthAccountRepository oauthAccountRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public SignUpResponse signUp(SignUpRequest request) {
        User user = userRegistrationService.register(
                request.email(), request.password(), request.name(), Role.USER);

        return new SignUpResponse(user.getId(), user.getEmail(), user.getName(), Role.USER.name());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        /*
         * 존재하지 않는 이메일과 틀린 비밀번호는 같은 예외 · 같은 코드 · 같은 메시지여야 한다.
         * 응답이 달라지면 공격자가 어떤 이메일이 가입돼 있는지 알아낼 수 있다.
         *
         * 본문뿐 아니라 걸리는 시간도 같아야 한다. 사용자를 못 찾았다고 바로 예외를 던지면
         * BCrypt(work factor 12, 수백 ms) 비교를 건너뛰게 되어, 응답 본문이 같아도
         * 응답 시간만 재면 가입 여부를 알 수 있다. 그래서 못 찾은 경우에도 더미 Hash로
         * 비교를 한 번 수행한 뒤 같은 예외를 던진다.
         */
        User user = userRepository.findByEmail(EmailNormalizer.normalize(request.email()))
                .orElse(null);

        if (user == null) {
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        IssuedToken token = jwtTokenProvider.issue(user.getId(), user.primaryRole());
        return new LoginResponse(token.accessToken(), "Bearer", token.expiresAt());
    }

    /**
     * 구글 로그인. 프론트가 받은 액세스 토큰으로 구글 userinfo 를 조회해 신원을 확인한 뒤,
     * 이미 연결된 계정이면 그 회원으로, 처음이면 같은 이메일 회원에 연결하거나 새로 만든다.
     * 이후는 일반 로그인과 똑같은 우리 JWT 를 발급한다.
     */
    public LoginResponse googleLogin(String googleAccessToken) {
        GoogleUserInfoResponse info = googleApiClient.getUserInfo(googleAccessToken);
        // 확인 안 된 이메일로는 연결도 생성도 하지 않는다. 연결하면 그 주소의 기존 회원을 가로채고,
        // 생성하면 실제 주인이 나중에 가입하지 못하게 주소를 선점한다.
        if (info == null || !Boolean.TRUE.equals(info.verifiedEmail())) {
            throw new BusinessException(ErrorCode.SOCIAL_LOGIN_FAILED);
        }
        return loginWithSocial(PROVIDER_GOOGLE, info.id(), info.email(), info.name());
    }

    /**
     * 네이버 로그인. 프론트가 받은 인가 코드·state 로 서버가 토큰을 교환하고 사용자 정보를 조회한다.
     * 네이버는 이메일을 주므로 구글과 동일하게 이메일 기준으로 연결·생성한다.
     */
    public LoginResponse naverLogin(String code, String state) {
        NaverUserInfoResponse info = naverApiClient.getUserInfoByCode(code, state);
        NaverUserInfoResponse.Response r = info == null ? null : info.response();
        String email = r == null ? null : r.email();
        String name = r == null ? null : r.name();
        String providerId = r == null ? null : r.id();
        return loginWithSocial(PROVIDER_NAVER, providerId, email, name);
    }

    /**
     * 카카오 로그인. 카카오는 비즈앱이 아니면 이메일을 주지 않는다(닉네임만).
     * 방안 B: 이메일을 못 받으면 카카오 식별자 기반 placeholder 이메일로 로그인시킨다.
     * placeholder 는 카카오 id 로 고정돼 재로그인 시 같은 회원을 가리킨다.
     */
    public LoginResponse kakaoLogin(String code, String redirectUri) {
        KakaoUserInfoResponse info = kakaoApiClient.getUserInfoByCode(code, redirectUri);
        Long id = info == null ? null : info.id();
        KakaoUserInfoResponse.KakaoAccount account = info == null ? null : info.kakaoAccount();
        // 카카오가 소유를 확인한 이메일만 쓴다. 확인 안 된 주소로 기존 회원에 연결되면 계정을 가로챈다.
        String email = account == null || !Boolean.TRUE.equals(account.isEmailVerified()) ? null : account.email();
        String nickname = account == null || account.profile() == null ? null : account.profile().nickname();
        String providerId = id == null ? null : String.valueOf(id);

        // 카카오 이메일 미제공(또는 미확인) 시 식별자 기반 placeholder 로 대체(방안 B).
        // 이 도메인은 일반 가입이 막혀 있어, placeholder 는 해당 카카오 계정만 가리킨다.
        if ((email == null || email.isBlank()) && providerId != null && !providerId.isBlank()) {
            email = "kakao_" + providerId + UserRegistrationService.SOCIAL_PLACEHOLDER_DOMAIN;
        }
        return loginWithSocial(PROVIDER_KAKAO, providerId, email, nickname);
    }

    /*
     * 제공자 공통 처리. 이미 연결된 소셜 계정이면 그 회원으로, 처음이면 같은 이메일 회원에
     * 연결하거나 새로 만든 뒤, 일반 로그인과 똑같은 우리 JWT 를 발급한다.
     * 이메일은 필수다(방안 A) — 없으면 실패로 처리해 users.email(NOT NULL·UNIQUE)을 지킨다.
     */
    private LoginResponse loginWithSocial(String provider, String providerId, String email, String name) {
        if (providerId == null || providerId.isBlank() || email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.SOCIAL_LOGIN_FAILED);
        }

        // 트랜잭션은 DB 작업에만 건다. 제공자 조회까지 안에 두면 응답이 느릴 때 커넥션을 붙잡고 있어
        // 풀이 바닥나고 일반 로그인까지 멈춘다. 그래서 호출부(google/naver/kakaoLogin)는 트랜잭션 밖이다.
        return transactionTemplate.execute(status -> {
            User user = oauthAccountRepository.findByProviderAndProviderId(provider, providerId)
                    .map(OauthAccount::getUser)
                    .orElseGet(() -> linkOrCreateSocialUser(provider, providerId, email, name));

            IssuedToken token = jwtTokenProvider.issue(user.getId(), user.primaryRole());
            return new LoginResponse(token.accessToken(), "Bearer", token.expiresAt());
        });
    }

    /*
     * 이 소셜 계정이 처음 들어온 경우다. 같은 이메일로 가입한 회원이 있으면 그 회원에 연결하고,
     * 없으면 비밀번호 없는 소셜 회원(USER)을 새로 만든다. 그런 뒤 연결 기록을 남긴다.
     */
    private User linkOrCreateSocialUser(String provider, String providerId, String rawEmail, String name) {
        String email = EmailNormalizer.normalize(rawEmail);
        LocalDateTime now = LocalDateTime.now(clock);

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(
                        User.createOauth(email, resolveName(name, email), Role.USER, now)));

        oauthAccountRepository.save(OauthAccount.of(user, provider, providerId, now));
        return user;
    }

    /** 제공자가 이름을 안 주면 이메일 앞부분을 이름으로 쓴다. */
    private String resolveName(String providerName, String email) {
        if (providerName != null && !providerName.isBlank()) {
            return providerName;
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
