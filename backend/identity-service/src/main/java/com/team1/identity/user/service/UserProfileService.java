package com.team1.identity.user.service;

import com.team1.identity.common.exception.BusinessException;
import com.team1.identity.common.exception.ErrorCode;
import com.team1.identity.common.security.CurrentUser;
import com.team1.identity.user.dto.ChangeNameRequest;
import com.team1.identity.user.dto.ChangePasswordRequest;
import com.team1.identity.user.dto.ChangeProfileImageRequest;
import com.team1.identity.user.dto.MyProfileResponse;
import com.team1.identity.user.entity.User;
import com.team1.identity.user.repository.UserRepository;
import com.team1.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
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
    public boolean isNameAvailable(String name) {
        Long currentUserId = CurrentUser.require().userId();
        return !userRepository.existsByNameAndIdNot(name, currentUserId);
    }

    @Transactional
    public MyProfileResponse changeName(ChangeNameRequest request) {
        User user = findCurrentUser();

        if (userRepository.existsByNameAndIdNot(request.name(), user.getId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_NAME);
        }

        user.changeName(request.name());
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
                user.getId(), user.getEmail(), user.getName(), user.primaryRole().name(), user.getProfileImageUrl(),
                user.getPasswordHash() != null);
    }
}
