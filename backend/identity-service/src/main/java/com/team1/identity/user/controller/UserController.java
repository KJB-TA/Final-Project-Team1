package com.team1.identity.user.controller;

import com.team1.identity.common.response.ApiResponse;
import com.team1.identity.user.dto.ChangeNicknameRequest;
import com.team1.identity.user.dto.ChangePasswordRequest;
import com.team1.identity.user.dto.ChangeProfileImageRequest;
import com.team1.identity.user.dto.MyProfileResponse;
import com.team1.identity.user.dto.NicknameAvailabilityResponse;
import com.team1.identity.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User", description = "내 프로필 조회 · 닉네임/비밀번호 변경 — 인증 필요")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserProfileService userProfileService;

    @Operation(summary = "내 프로필 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHENTICATED — Token 없음·만료·서명 오류")
    })
    @GetMapping("/me")
    public ApiResponse<MyProfileResponse> getMe() {
        return ApiResponse.ok(userProfileService.getMyProfile());
    }

    @Operation(summary = "닉네임 중복 확인")
    @GetMapping("/me/nickname-availability")
    public ApiResponse<NicknameAvailabilityResponse> checkNicknameAvailability(@RequestParam String nickname) {
        return ApiResponse.ok(new NicknameAvailabilityResponse(userProfileService.isNicknameAvailable(nickname)));
    }

    @Operation(summary = "프로필 이미지 변경")
    @PatchMapping("/me/profile-image")
    public ApiResponse<MyProfileResponse> changeProfileImage(@Valid @RequestBody ChangeProfileImageRequest request) {
        return ApiResponse.ok(userProfileService.changeProfileImage(request));
    }

    @Operation(summary = "닉네임 변경")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "INVALID_REQUEST — 형식 위반"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHENTICATED — Token 없음·만료·서명 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "DUPLICATE_NICKNAME — 다른 회원이 쓰는 닉네임")
    })
    @PatchMapping("/me/nickname")
    public ApiResponse<MyProfileResponse> changeNickname(@Valid @RequestBody ChangeNicknameRequest request) {
        return ApiResponse.ok(userProfileService.changeNickname(request));
    }

    @Operation(
            summary = "비밀번호 변경",
            description = "현재 비밀번호가 일치해야 변경된다. 존재하지 않는 사용자와 틀린 현재 비밀번호는 응답으로 구분할 수 없다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "INVALID_REQUEST — 새 비밀번호 정책 위반"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHENTICATED — Token 없음·만료·서명 오류, 또는 INVALID_CREDENTIALS — 현재 비밀번호 불일치")
    })
    @PatchMapping("/me/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userProfileService.changePassword(request);
        return ApiResponse.ok(null);
    }
}
