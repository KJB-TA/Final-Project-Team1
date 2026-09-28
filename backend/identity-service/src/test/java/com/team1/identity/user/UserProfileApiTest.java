package com.team1.identity.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.team1.identity.auth.jwt.JwtTokenProvider;
import com.team1.identity.support.ApiTestSupport;
import com.team1.identity.user.entity.Role;
import com.team1.identity.user.entity.User;
import com.team1.identity.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileApiTest extends ApiTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("인증 없이 내 프로필을 조회하면 401이다")
    void 인증_없음() {
        ResponseEntity<JsonNode> response = get("/api/v1/users/me", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("로그인한 사용자는 자기 프로필을 조회할 수 있다")
    void 내_프로필_조회() {
        String email = uniqueEmail();
        post("/api/v1/auth/signup", """
                {"email":"%s","password":"password123","name":"테스터"}
                """.formatted(email));
        String token = loginAndGetToken(email, "password123");

        ResponseEntity<JsonNode> response = get("/api/v1/users/me", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("data").path("email").asText()).isEqualTo(email);
        assertThat(response.getBody().path("data").path("name").asText()).isEqualTo("테스터");
        assertThat(response.getBody().path("data").path("role").asText()).isEqualTo("USER");
        assertThat(response.getBody().path("data").path("hasPassword").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("닉네임을 변경하면 이후 조회에도 반영된다")
    void 닉네임_변경() {
        String email = uniqueEmail();
        post("/api/v1/auth/signup", """
                {"email":"%s","password":"password123","name":"테스터"}
                """.formatted(email));
        String token = loginAndGetToken(email, "password123");

        ResponseEntity<JsonNode> response = patch("/api/v1/users/me/name", """
                {"name":"새이름"}
                """, token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("data").path("name").asText()).isEqualTo("새이름");
        assertThat(get("/api/v1/users/me", token).getBody().path("data").path("name").asText())
                .isEqualTo("새이름");
    }

    @Test
    @DisplayName("프로필 이미지를 변경하면 이후 조회에도 반영된다")
    void 프로필_이미지_변경() {
        String email = uniqueEmail();
        post("/api/v1/auth/signup", """
                {"email":"%s","password":"password123","name":"테스터"}
                """.formatted(email));
        String token = loginAndGetToken(email, "password123");

        ResponseEntity<JsonNode> response = patch("/api/v1/users/me/profile-image", """
                {"imageUrl":"https://res.cloudinary.com/demo/image/upload/avatar.png"}
                """, token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("data").path("profileImageUrl").asText())
                .isEqualTo("https://res.cloudinary.com/demo/image/upload/avatar.png");
        assertThat(get("/api/v1/users/me", token).getBody().path("data").path("profileImageUrl").asText())
                .isEqualTo("https://res.cloudinary.com/demo/image/upload/avatar.png");
    }

    @Test
    @DisplayName("아무도 쓰지 않는 닉네임은 사용 가능하다")
    void 닉네임_중복확인_사용가능() {
        String email = uniqueEmail();
        post("/api/v1/auth/signup", """
                {"email":"%s","password":"password123","name":"테스터"}
                """.formatted(email));
        String token = loginAndGetToken(email, "password123");

        ResponseEntity<JsonNode> response = get("/api/v1/users/me/name-availability?name=아무도안쓰는닉네임", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("data").path("available").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("다른 사용자가 쓰는 닉네임은 사용 불가이고, 변경을 시도하면 409다")
    void 닉네임_중복확인_사용중() {
        String ownerEmail = uniqueEmail();
        post("/api/v1/auth/signup", """
                {"email":"%s","password":"password123","name":"겹치는닉네임"}
                """.formatted(ownerEmail));

        String otherEmail = uniqueEmail();
        post("/api/v1/auth/signup", """
                {"email":"%s","password":"password123","name":"테스터"}
                """.formatted(otherEmail));
        String otherToken = loginAndGetToken(otherEmail, "password123");

        ResponseEntity<JsonNode> checkResponse =
                get("/api/v1/users/me/name-availability?name=겹치는닉네임", otherToken);
        assertThat(checkResponse.getBody().path("data").path("available").asBoolean()).isFalse();

        ResponseEntity<JsonNode> changeResponse = patch("/api/v1/users/me/name", """
                {"name":"겹치는닉네임"}
                """, otherToken);

        assertThat(changeResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(changeResponse)).isEqualTo("DUPLICATE_NAME");
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 401이고 비밀번호가 바뀌지 않는다")
    void 현재_비밀번호_불일치() {
        String email = uniqueEmail();
        post("/api/v1/auth/signup", """
                {"email":"%s","password":"password123","name":"테스터"}
                """.formatted(email));
        String token = loginAndGetToken(email, "password123");

        ResponseEntity<JsonNode> response = patch("/api/v1/users/me/password", """
                {"currentPassword":"wrongpassword1","newPassword":"newpassword1"}
                """, token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(response)).isEqualTo("INVALID_CREDENTIALS");

        // 원래 비밀번호로 여전히 로그인된다
        assertThat(loginAndGetToken(email, "password123")).isNotBlank();
    }

    @Test
    @DisplayName("현재 비밀번호가 맞으면 비밀번호가 바뀌고, 새 비밀번호로 다시 로그인된다")
    void 비밀번호_변경() {
        String email = uniqueEmail();
        post("/api/v1/auth/signup", """
                {"email":"%s","password":"password123","name":"테스터"}
                """.formatted(email));
        String token = loginAndGetToken(email, "password123");

        ResponseEntity<JsonNode> response = patch("/api/v1/users/me/password", """
                {"currentPassword":"password123","newPassword":"newpassword1"}
                """, token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginAndGetToken(email, "newpassword1")).isNotBlank();
    }

    @Test
    @DisplayName("소셜 가입 회원은 hasPassword 가 false 다 - 화면이 비밀번호 변경을 숨긴다")
    void 소셜_회원은_비밀번호가_없다() {
        User user = userRepository.save(User.createOauth(uniqueEmail(), "소셜회원", Role.USER, LocalDateTime.now()));
        String token = jwtTokenProvider.issue(user.getId(), user.primaryRole()).accessToken();

        ResponseEntity<JsonNode> response = get("/api/v1/users/me", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("data").path("hasPassword").asBoolean()).isFalse();
    }
}
