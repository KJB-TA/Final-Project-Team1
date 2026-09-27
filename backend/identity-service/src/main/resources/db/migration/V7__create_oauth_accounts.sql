-- 소셜 로그인 계정 연결. 한 회원이 여러 provider 를 연결할 수 있고,
-- (provider, provider_id) 는 전역에서 유일하다 — 같은 구글 계정이 두 회원에 붙지 않는다.
CREATE TABLE oauth_accounts (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    provider    VARCHAR(20)  NOT NULL,
    provider_id VARCHAR(100) NOT NULL,
    linked_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_oauth_provider_account UNIQUE (provider, provider_id),
    CONSTRAINT fk_oauth_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 한 회원의 연결 목록 조회용
CREATE INDEX idx_oauth_user ON oauth_accounts (user_id);
