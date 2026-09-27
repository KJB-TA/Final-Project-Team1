-- V4 에서 reservations 가 아직 없어 미뤄둔 FK. reservations 는 V6 에서 생겼다.
ALTER TABLE payment_transactions
    ADD CONSTRAINT fk_payment_transactions_reservation
        FOREIGN KEY (ref_id) REFERENCES reservations (id);
