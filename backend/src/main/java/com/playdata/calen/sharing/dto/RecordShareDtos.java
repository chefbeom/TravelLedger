package com.playdata.calen.sharing.dto;

import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class RecordShareDtos {
    private RecordShareDtos() {}
    public record Create(@NotNull @Positive Long groupId, @NotNull RecordShareKind kind,
                         @NotNull @Positive Long sourceId,
                         @NotEmpty @Size(max = 50) List<@NotNull @Positive Long> recipientIds,
                         @Size(max = 500, message = "공유 메모는 500자까지 입력할 수 있습니다.") String shareMemo) {
        public Create(Long groupId, RecordShareKind kind, Long sourceId, List<Long> recipientIds) {
            this(groupId, kind, sourceId, recipientIds, null);
        }
    }
    public record Member(Long userId, String loginId, String displayName, boolean self) {}
    public record Group(Long id, String name, Long ownerId, List<Member> members) {}
    public record Response(Long id, Long groupId, String groupName, RecordShareKind kind, Long sourceId,
                           Long senderId, String senderName, Long recipientId, String recipientName,
                           RecordShareStatus status, String title, LedgerEntryResponse ledger,
                           Long importedLedgerEntryId, LocalDateTime createdAt, LocalDateTime respondedAt,
                           String shareMemo) {}
    public record Page(List<Response> items, int page, int size, long totalElements, int totalPages) {}
    public record Counts(long ledger, long travel) {}
}
