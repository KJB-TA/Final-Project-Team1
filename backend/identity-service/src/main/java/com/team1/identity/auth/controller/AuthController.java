package com.team1.identity.auth.controller;

import com.team1.identity.auth.dto.GoogleLoginRequest;
import com.team1.identity.auth.dto.KakaoLoginRequest;
import com.team1.identity.auth.dto.NaverLoginRequest;
import com.team1.identity.auth.dto.LoginRequest;
import com.team1.identity.auth.dto.LoginResponse;
import com.team1.identity.auth.dto.SignUpRequest;
import com.team1.identity.auth.dto.SignUpResponse;
import com.team1.identity.auth.service.AuthService;
import com.team1.identity.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "회원가입 · 로그인 (인증 불필요)")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "회원가입",
            description = """
                    이메일은 앞뒤 공백을 제거하고 소문자로 정규화해 저장한다.
                    비밀번호는 8~64자이며 영문과 숫자를 각각 1자 이상 포함해야 한다.
                    사용자와 USER Role은 한 Transaction에 저장되며, 동시에 같은 이메일로
                    요청해도 한 건만 생성된다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "가입 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "INVALID_REQUEST — 이메일 형식 아님·비밀번호 정책 위반·본문 파싱 불가 (미생성)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "DUPLICATE_EMAIL — 이미 가입된 이메일 (미생성)")
    })
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignUpResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        return ApiResponse.ok(authService.signUp(request));
    }

    @Operation(
            summary = "로그인",
            description = """
                    성공 시 Access Token(수명 1시간)과 만료 시각을 반환한다.
                    Token 클레임은 sub에 userId(문자열), role에 Role 문자열을 담는다.
                    expiresAt은 ISO-8601 UTC 초 단위이며 Token의 exp 클레임과 동일한 시각이다.
                    존재하지 않는 이메일과 틀린 비밀번호는 응답으로 구분할 수 없다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "INVALID_CREDENTIALS — 이메일 미존재·비밀번호 불일치 (두 경우 구분 불가). WWW-Authenticate: Bearer 부착")
    })
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @Operation(
            summary = "구글 로그인",
            description = """
                    프론트에서 구글로 받은 액세스 토큰을 넘기면, 서버가 그 토큰으로 구글 사용자
                    정보를 조회해 신원을 확인한다. 처음 로그인하는 구글 계정은 같은 이메일 회원에
                    연결하거나 새 회원(USER)으로 만든 뒤, 일반 로그인과 동일한 Access Token을 반환한다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "로그인 성공(신규 계정은 자동 생성)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "SOCIAL_LOGIN_FAILED — 구글 토큰이 없거나 유효하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "DEPENDENCY_UNAVAILABLE — 구글 조회 실패(장애·네트워크)")
    })
    @PostMapping("/google")
    public ApiResponse<LoginResponse> googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
        return ApiResponse.ok(authService.googleLogin(request.googleAccessToken()));
    }

    @Operation(
            summary = "네이버 로그인",
            description = """
                    프론트에서 네이버로 받은 액세스 토큰을 넘기면, 서버가 그 토큰으로 네이버 사용자
                    정보를 조회해 신원을 확인한다. 처음 로그인하는 네이버 계정은 같은 이메일 회원에
                    연결하거나 새 회원(USER)으로 만든 뒤, 일반 로그인과 동일한 Access Token을 반환한다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "로그인 성공(신규 계정은 자동 생성)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "SOCIAL_LOGIN_FAILED — 네이버 토큰이 없거나 유효하지 않음·이메일 미제공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "DEPENDENCY_UNAVAILABLE — 네이버 조회 실패(장애·네트워크)")
    })
    @PostMapping("/naver")
    public ApiResponse<LoginResponse> naverLogin(@Valid @RequestBody NaverLoginRequest request) {
        return ApiResponse.ok(authService.naverLogin(request.code(), request.state()));
    }

    @Operation(
            summary = "카카오 로그인",
            description = """
                    프론트에서 카카오로 받은 인가 코드를 넘기면, 서버가 REST 키로 토큰을 교환하고
                    카카오 사용자 정보를 조회해 신원을 확인한다. 카카오 이메일이 없으면(비즈앱 아님)
                    식별자 기반 placeholder 이메일로 처리한다(방안 B). 처음 로그인하는 계정은 같은
                    이메일 회원에 연결하거나 새 회원(USER)으로 만든 뒤 일반 로그인과 동일한 Access Token을 반환한다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "로그인 성공(신규 계정은 자동 생성)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "SOCIAL_LOGIN_FAILED — 카카오 인가 코드가 없거나 유효하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "DEPENDENCY_UNAVAILABLE — 카카오 조회 실패(장애·네트워크)")
    })
    @PostMapping("/kakao")
    public ApiResponse<LoginResponse> kakaoLogin(@Valid @RequestBody KakaoLoginRequest request) {
        return ApiResponse.ok(authService.kakaoLogin(request.code(), request.redirectUri()));
    }
}
