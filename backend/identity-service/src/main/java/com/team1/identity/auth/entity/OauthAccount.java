package com.team1.identity.auth.entity;

import com.team1.identity.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 소셜 로그인 계정과 우리 회원(User)의 연결.
 * (provider, provider_id) 는 유일하다 — 같은 소셜 계정이 두 회원에 붙지 않는다.
 */
@Entity
@Table(
        name = "oauth_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_oauth_provider_account",
                columnNames = {"provider", "provider_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OauthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_id", nullable = false, length = 100)
    private String providerId;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private LocalDateTime linkedAt;

    private OauthAccount(User user, String provider, String providerId, LocalDateTime linkedAt) {
        this.user = user;
        this.provider = provider;
        this.providerId = providerId;
        this.linkedAt = linkedAt;
    }

    public static OauthAccount of(User user, String provider, String providerId, LocalDateTime linkedAt) {
        return new OauthAccount(user, provider, providerId, linkedAt);
    }
}
