-- 같은 회원이 같은 회차에 유효 예약(PENDING·CONFIRMED)을 둘 이상 갖지 못하게 DB 가 막는다.
-- 서비스의 "있는지 보고 넣기" 는 동시 요청 두 개가 함께 통과할 수 있다(더블클릭).
-- 취소·만료되면 값이 NULL 이 되고, UNIQUE 는 NULL 끼리 겹쳐도 막지 않으므로 재예약은 그대로 된다.
ALTER TABLE reservations
    ADD COLUMN active_holder VARCHAR(64)
        GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING', 'CONFIRMED')
                                  THEN CONCAT(round_id, ':', user_id) END) STORED
        COMMENT '유효 예약일 때만 round_id:user_id. JPA 는 매핑하지 않는다',
    ADD CONSTRAINT uk_reservations_active_holder UNIQUE (active_holder);
