package com.team1.identity.user.dto;

public record MyProfileResponse(
        Long id,
        String email,
        String name,
        // 화면에 보이는 이름. name 은 실명이다.
        String nickname,
        String role,
        String profileImageUrl,
        // 소셜 가입 회원은 비밀번호가 없다. 화면이 비밀번호 변경을 숨기는 데 쓴다.
        boolean hasPassword
) {
}
