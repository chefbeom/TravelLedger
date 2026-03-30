package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.SupportInquiry;
import com.playdata.calen.account.domain.SupportInquiryStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportInquiryRepository extends JpaRepository<SupportInquiry, Long> {

    List<SupportInquiry> findAllBySenderIdOrderByCreatedAtDescIdDesc(Long senderId);

    List<SupportInquiry> findAllByAdminDeletedFalseOrderByCreatedAtDescIdDesc();

    long countByStatusAndAdminDeletedFalse(SupportInquiryStatus status);
}
