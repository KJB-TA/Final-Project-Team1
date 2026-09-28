package com.team1.identity.user.service;

import com.team1.identity.common.exception.BusinessException;
import com.team1.identity.common.exception.ErrorCode;
import com.team1.identity.common.security.CurrentUser;
import com.team1.identity.user.dto.ChangeNicknameRequest;
import com.team1.identity.user.dto.ChangePasswordRequest;
import com.team1.identity.user.dto.ChangeProfileImageRequest;
import com.team1.identity.user.dto.MyProfileResponse;
import com.team1.identity.user.entity.User;
import com.team1.identity.user.repository.UserRepository;
import com.team1.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile() {
        User user = findCurrentUser();
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public boolean isNicknameAvailable(String nickname) {
        Long currentUserId = CurrentUser.require().userId();
        return !userRepository.existsByNicknameAndIdNot(nickname, currentUserId);
    }

    @Transactional
    public MyProfileResponse changeNickname(ChangeNicknameRequest request) {
        User user = findCurrentUser();

        if (userRepository.existsByNicknameAndIdNot(request.nickname(), user.getId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        user.changeNickname(request.nickname());
        try {
            // 사전 확인과 저장 사이에 같은 닉네임이 먼저 저장될 수 있다. 최종 보장은 UNIQUE 제약이다.
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }
        return toResponse(user);
    }

    @Transactional
    public MyProfileResponse changeProfileImage(ChangeProfileImageRequest request) {
        User user = findCurrentUser();
        user.changeProfileImage(request.imageUrl());
        return toResponse(user);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        User user = findCurrentUser();

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
    }

    private User findCurrentUser() {
        AuthenticatedUser current = CurrentUser.require();
        return userRepository.findById(current.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private MyProfileResponse toResponse(User user) {
        return new MyProfileResponse(
                user.getId(), user.getEmail(), user.getName(), user.getNickname(), user.primaryRole().name(),
                user.getProfileImageUrl(),
                user.getPasswordHash() != null);
    }
}
