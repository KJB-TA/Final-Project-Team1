package com.team1.identity.user.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    // 소셜 로그인 사용자는 비밀번호가 없어 null 이다(V6). 이메일/비밀번호 가입자만 값을 가진다.
    @Column(name = "password_hash", length = 60)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    private Set<GrantedRole> roles = new LinkedHashSet<>();

    private User(String email, String passwordHash, String name, Role role, LocalDateTime now) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.createdAt = now;
        this.roles.add(new GrantedRole(role, now));
    }

    public static User create(String email, String passwordHash, String name, Role role, LocalDateTime now) {
        return new User(email, passwordHash, name, role, now);
    }

    /*
     * 소셜 로그인 최초 진입 시 만드는 사용자다. 비밀번호가 없어 password_hash 는 null 이며,
     * 이 계정으로는 이메일/비밀번호 로그인을 할 수 없다(BCrypt 비교가 항상 실패).
     */
    public static User createOauth(String email, String name, Role role, LocalDateTime now) {
        return new User(email, null, name, role, now);
    }

    /*
     * 계약서의 JWT 클레임 role은 단수다. Sprint 1은 1인 1Role이지만
     * 여러 개가 부여된 경우에도 결과가 흔들리지 않도록 가장 강한 Role을 고른다.
     * (enum 선언 순서: USER < ORGANIZER < SUPER_ADMIN)
     */
    /*
     * 비밀번호 Hash가 Log에 새지 않도록 표시할 필드를 명시한다.
     * 누군가 나중에 @ToString을 붙여도 이 메서드가 우선한다.
     */
    @Override
    public String toString() {
        return "User[id=" + id + ", email=" + email + ", name=" + name + "]";
    }

    public void changeName(String name) {
        this.name = name;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void changeProfileImage(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }

    public Role primaryRole() {
        return roles.stream()
                .map(GrantedRole::getRole)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElseThrow(() -> new IllegalStateException("Role이 없는 사용자입니다. id=" + id));
    }

    public void addRole(Role role, LocalDateTime grantedAt) {
        roles.add(new GrantedRole(role, grantedAt));
    }
}
