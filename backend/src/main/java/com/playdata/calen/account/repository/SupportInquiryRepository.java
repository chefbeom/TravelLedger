package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.SupportInquiry;
import com.playdata.calen.account.domain.SupportInquiryStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportInquiryRepository extends JpaRepository<SupportInquiry, Long> {

    Page<SupportInquiry> findAllBySenderIdOrderByCreatedAtDescIdDesc(Long senderId, Pageable pageable);

    List<SupportInquiry> findAllByAdminDeletedFalseOrderByCreatedAtDescIdDesc();

    long countByStatusAndAdminDeletedFalse(SupportInquiryStatus status);

    long countBySenderIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long senderId,
            LocalDateTime from,
            LocalDateTime to
    );
}
