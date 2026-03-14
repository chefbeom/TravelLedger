package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.PaymentMethod;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {

    boolean existsByOwnerId(Long ownerId);

    List<PaymentMethod> findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(Long ownerId);

    java.util.Optional<PaymentMethod> findByOwnerIdAndNameIgnoreCase(Long ownerId, String name);

    java.util.Optional<PaymentMethod> findByIdAndOwnerId(Long id, Long ownerId);
}
