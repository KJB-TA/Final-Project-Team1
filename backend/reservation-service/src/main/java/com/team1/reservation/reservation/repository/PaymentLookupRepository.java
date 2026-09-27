package com.team1.reservation.reservation.repository;

import com.team1.payment.PaymentTransaction;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/*
 예약 목록의 환불 상태를 파생하려면 결제 행을 일괄로 읽어야 한다.*/
public interface PaymentLookupRepository extends Repository<PaymentTransaction, Long> {

    List<PaymentTransaction> findByRefIdIn(Collection<Long> refIds);

    /** 정산용 - 결제 시각이나 환불 시각이 [from, to) 에 든 결제. 결제된 적 없는 건은 뺀다. */
    @Query("select p from PaymentTransaction p where p.paidAt is not null and ("
            + "(p.paidAt >= :from and p.paidAt < :to) "
            + "or (p.cancelledAt >= :from and p.cancelledAt < :to))")
    List<PaymentTransaction> findSettlementEvents(@Param("from") Instant from, @Param("to") Instant to);
}
