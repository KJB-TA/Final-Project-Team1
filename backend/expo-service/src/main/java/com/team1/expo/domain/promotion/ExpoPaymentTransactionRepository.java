package com.team1.expo.domain.promotion;

import com.team1.payment.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExpoPaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByRefId(Long promotionId);
    Optional<PaymentTransaction> findByPaymentId(String paymentId);

    /** 정산용 - 결제 시각이나 환불 시각이 [from, to) 에 든 결제. 결제된 적 없는 건은 뺀다. */
    @Query("select p from PaymentTransaction p where p.paidAt is not null and ("
            + "(p.paidAt >= :from and p.paidAt < :to) "
            + "or (p.cancelledAt >= :from and p.cancelledAt < :to))")
    List<PaymentTransaction> findSettlementEvents(@Param("from") Instant from, @Param("to") Instant to);
}
