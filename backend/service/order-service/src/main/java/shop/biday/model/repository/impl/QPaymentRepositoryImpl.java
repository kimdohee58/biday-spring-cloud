package shop.biday.model.repository.impl;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import shop.biday.model.dto.PaymentData;
import shop.biday.model.entity.QPaymentEntity;
import shop.biday.model.repository.QPaymentRepository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class QPaymentRepositoryImpl implements QPaymentRepository {

    private final JPAQueryFactory queryFactory;
    private final QPaymentEntity qPayment = QPaymentEntity.paymentEntity;

    @Override
    public List<PaymentData> findByUser(String userId) {
        return queryFactory
                .select(Projections.constructor(PaymentData.class,
                        qPayment.awardId,
                        qPayment.paymentKey,
                        qPayment.totalAmount,
                        qPayment.orderId,
                        qPayment.approvedAt
                ))
                .from(qPayment)
                .where(qPayment.userId.eq(userId))
                .orderBy(qPayment.createdAt.desc())
                .fetch();
    }
}
