package com.team1.identity.user.service;

import com.team1.identity.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 가입할 때 쓸 첫 닉네임을 정한다. 이름(실명)은 겹칠 수 있지만 닉네임은 유일해야 하므로,
 * 이름이 이미 누군가의 닉네임이면 '#숫자' 를 붙인다. 사용자는 마이페이지에서 원하는 닉네임으로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class NicknameGenerator {

    static final int MAX_LENGTH = 100;
    private static final int BASE_LENGTH_WITH_SUFFIX = 90;
    private static final int RANDOM_TRIES = 10;

    private final UserRepository userRepository;

    public String initialNickname(String name) {
        String base = name.length() > MAX_LENGTH ? name.substring(0, MAX_LENGTH) : name;
        if (!userRepository.existsByNickname(base)) {
            return base;
        }

        String prefix = base.length() > BASE_LENGTH_WITH_SUFFIX ? base.substring(0, BASE_LENGTH_WITH_SUFFIX) : base;
        for (int i = 0; i < RANDOM_TRIES; i++) {
            String candidate = prefix + "#" + ThreadLocalRandom.current().nextInt(1000, 10000);
            if (!userRepository.existsByNickname(candidate)) {
                return candidate;
            }
        }
        // 흔한 이름이라 네 자리가 다 찼다면 사실상 겹치지 않는 값으로 넘어간다.
        return prefix + "#" + UUID.randomUUID().toString().substring(0, 8);
    }
}
