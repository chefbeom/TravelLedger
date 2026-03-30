package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelPlanShare;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPlanShareRepository extends JpaRepository<TravelPlanShare, Long> {

    Optional<TravelPlanShare> findByPlanIdAndRecipientId(Long planId, Long recipientId);

    List<TravelPlanShare> findAllByRecipientIdOrderByCreatedAtDescIdDesc(Long recipientId);

    Optional<TravelPlanShare> findByIdAndRecipientId(Long id, Long recipientId);

    void deleteAllByPlanId(Long planId);
}
