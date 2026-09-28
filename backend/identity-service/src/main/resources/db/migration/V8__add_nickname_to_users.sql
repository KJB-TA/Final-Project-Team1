-- 이름(name)은 실명이라 겹쳐도 되고, 화면에 보이는 이름은 닉네임(nickname)으로 따로 둔다. 닉네임은 유일하다.
-- 기존 회원은 이름을 닉네임으로 옮기되, 같은 이름이 여럿이면 가장 먼저 가입한 회원만 그대로 쓰고
-- 나머지는 '#id' 를 붙인다(id 는 유일하므로 결과도 유일하다). 길이 한도(100)를 넘지 않게 이름을 자른다.
ALTER TABLE users
    ADD COLUMN nickname VARCHAR(100) NULL AFTER name;

UPDATE users u
    JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY name ORDER BY id) AS rn FROM users) t ON u.id = t.id
SET u.nickname = CASE WHEN t.rn = 1 THEN u.name ELSE CONCAT(LEFT(u.name, 80), '#', u.id) END;

ALTER TABLE users
    MODIFY COLUMN nickname VARCHAR(100) NOT NULL,
    ADD CONSTRAINT uk_users_nickname UNIQUE (nickname);
