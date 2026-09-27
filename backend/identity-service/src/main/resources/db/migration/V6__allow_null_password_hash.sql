-- 소셜 로그인(구글 등) 사용자는 비밀번호가 없다.
-- 이메일/비밀번호 가입자만 password_hash 를 가진다.
ALTER TABLE users
    MODIFY COLUMN password_hash VARCHAR(60) NULL;
