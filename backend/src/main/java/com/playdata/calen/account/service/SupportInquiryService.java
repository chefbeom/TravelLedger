package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SupportInquiry;
import com.playdata.calen.account.domain.SupportInquiryStatus;
import com.playdata.calen.account.dto.SupportInquiryPageResponse;
import com.playdata.calen.account.dto.SupportInquiryResponse;
import com.playdata.calen.account.repository.SupportInquiryRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupportInquiryService {

    private static final int MAX_DAILY_INQUIRIES = 3;
    private static final int MAX_MY_INQUIRIES_PAGE_SIZE = 5;

    private final AppUserService appUserService;
    private final SupportInquiryRepository supportInquiryRepository;
    private final SupportInquiryStorageService supportInquiryStorageService;

    public SupportInquiryPageResponse getMyInquiries(Long userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_MY_INQUIRIES_PAGE_SIZE);
        Page<SupportInquiry> inquiryPage = supportInquiryRepository.findAllBySenderIdOrderByCreatedAtDescIdDesc(
                userId,
                PageRequest.of(safePage, safeSize)
        );
        return new SupportInquiryPageResponse(
                inquiryPage.getContent().stream().map(this::toResponse).toList(),
                inquiryPage.getNumber(),
                inquiryPage.getSize(),
                inquiryPage.getTotalElements(),
                inquiryPage.getTotalPages()
        );
    }

    public List<SupportInquiryResponse> getAdminInbox() {
        return supportInquiryRepository.findAllByAdminDeletedFalseOrderByCreatedAtDescIdDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public SupportInquiryResponse createInquiry(Long userId, String titleRaw, String contentRaw, MultipartFile attachment) {
        AppUser sender = appUserService.getRequiredUser(userId);
        validateDailyInquiryLimit(userId);
        String title = normalizeTitle(titleRaw);
        String content = normalizeContent(contentRaw);

        SupportInquiry inquiry = new SupportInquiry();
        inquiry.setSender(sender);
        inquiry.setTitle(title);
        inquiry.setContent(content);
        inquiry.setStatus(SupportInquiryStatus.PENDING);
        inquiry.setCreatedAt(LocalDateTime.now());
        inquiry.setUpdatedAt(LocalDateTime.now());

        SupportInquiryStorageService.StoredSupportAttachment storedAttachment = supportInquiryStorageService.store(userId, attachment);
        if (storedAttachment != null) {
            inquiry.setAttachmentOriginalFileName(storedAttachment.originalFileName());
            inquiry.setAttachmentStoragePath(storedAttachment.storagePath());
            inquiry.setAttachmentContentType(storedAttachment.contentType());
            inquiry.setAttachmentSize(storedAttachment.size());
        }

        return toResponse(supportInquiryRepository.save(inquiry));
    }

    @Transactional
    public SupportInquiryResponse reply(Long adminUserId, Long inquiryId, String replyContentRaw) {
        AppUser adminUser = appUserService.getRequiredUser(adminUserId);
        String replyContent = normalizeContent(replyContentRaw);

        SupportInquiry inquiry = supportInquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new NotFoundException("문의 내역을 찾을 수 없습니다."));

        inquiry.setReplyContent(replyContent);
        inquiry.setRepliedBy(adminUser);
        inquiry.setRepliedAt(LocalDateTime.now());
        inquiry.setStatus(SupportInquiryStatus.ANSWERED);
        inquiry.setAdminArchived(true);
        inquiry.setAdminDeleted(false);
        inquiry.setUpdatedAt(LocalDateTime.now());

        return toResponse(inquiry);
    }

    @Transactional
    public SupportInquiryResponse setArchived(Long inquiryId, boolean archived) {
        SupportInquiry inquiry = supportInquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new NotFoundException("문의 내역을 찾을 수 없습니다."));

        inquiry.setAdminArchived(archived);
        inquiry.setAdminDeleted(false);
        inquiry.setUpdatedAt(LocalDateTime.now());
        return toResponse(inquiry);
    }

    @Transactional
    public void deleteForAdmin(Long inquiryId) {
        SupportInquiry inquiry = supportInquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new NotFoundException("문의 내역을 찾을 수 없습니다."));

        inquiry.setAdminDeleted(true);
        inquiry.setUpdatedAt(LocalDateTime.now());
    }

    public AttachmentPayload loadAttachment(Long requesterUserId, boolean requesterAdmin, Long inquiryId) {
        SupportInquiry inquiry = supportInquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new NotFoundException("문의 내역을 찾을 수 없습니다."));

        if (!requesterAdmin && !inquiry.getSender().getId().equals(requesterUserId)) {
            throw new NotFoundException("첨부 이미지를 찾을 수 없습니다.");
        }
        if (!StringUtils.hasText(inquiry.getAttachmentStoragePath())) {
            throw new NotFoundException("첨부 이미지를 찾을 수 없습니다.");
        }

        Resource resource = supportInquiryStorageService.loadAsResource(inquiry.getAttachmentStoragePath());
        return new AttachmentPayload(
                inquiry.getAttachmentOriginalFileName(),
                inquiry.getAttachmentContentType(),
                resource
        );
    }

    public long countPendingInquiries() {
        return supportInquiryRepository.countByStatusAndAdminDeletedFalse(SupportInquiryStatus.PENDING);
    }

    private void validateDailyInquiryLimit(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        long dailyCount = supportInquiryRepository.countBySenderIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                userId,
                from,
                to
        );
        if (dailyCount >= MAX_DAILY_INQUIRIES) {
            throw new BadRequestException("문의는 하루에 최대 3개까지만 보낼 수 있습니다.");
        }
    }

    private SupportInquiryResponse toResponse(SupportInquiry inquiry) {
        AppUser sender = inquiry.getSender();
        AppUser repliedBy = inquiry.getRepliedBy();
        return new SupportInquiryResponse(
                inquiry.getId(),
                inquiry.getTitle(),
                inquiry.getContent(),
                inquiry.getStatus().name(),
                inquiry.getCreatedAt(),
                sender != null ? sender.getLoginId() : null,
                sender != null ? sender.getDisplayName() : null,
                inquiry.getAttachmentOriginalFileName(),
                inquiry.getAttachmentContentType(),
                StringUtils.hasText(inquiry.getAttachmentStoragePath())
                        ? "/api/support/inquiries/" + inquiry.getId() + "/attachment"
                        : null,
                inquiry.isAdminArchived(),
                inquiry.getReplyContent(),
                inquiry.getRepliedAt(),
                repliedBy != null ? repliedBy.getLoginId() : null,
                repliedBy != null ? repliedBy.getDisplayName() : null
        );
    }

    private String normalizeTitle(String titleRaw) {
        String title = titleRaw != null ? titleRaw.trim() : "";
        if (!StringUtils.hasText(title)) {
            throw new BadRequestException("문의 제목을 입력해 주세요.");
        }
        if (title.length() > 140) {
            throw new BadRequestException("문의 제목은 140자 이하로 입력해 주세요.");
        }
        return title;
    }

    private String normalizeContent(String contentRaw) {
        String content = contentRaw != null ? contentRaw.trim() : "";
        if (!StringUtils.hasText(content)) {
            throw new BadRequestException("문의 내용을 입력해 주세요.");
        }
        return content;
    }

    public record AttachmentPayload(
            String fileName,
            String contentType,
            Resource resource
    ) {
    }
}
