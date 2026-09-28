package com.team1.identity.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeNicknameRequest(

        @NotBlank
        @Size(max = 100)
        String nickname
) {
}
