package com.playdata.calen.ledger.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository.CompletedReviewRow;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class LedgerImageReviewSummaryTest {
    @Mock AppUserService users;
    @Mock LedgerImageAnalysisRequestRepository repository;
    @Spy ObjectMapper mapper = new ObjectMapper();
    @InjectMocks LedgerOcrService service;

    private CompletedReviewRow row(long id, String json) {
        return new CompletedReviewRow() {
            public Long getId() { return id; }
            public String getResultJson() { return json; }
        };
    }

    @Test void onlyUnfinishedUsableResultsAreReturnedWithoutTransactionContent() throws Exception {
        when(repository.findCompletedReviewRows(eq(7L), eq(0L), any())).thenReturn(List.of(
                row(1, "{\"suggestedEntries\":[{},{}],\"approvedEntryIndexes\":[0,0,-1,9,true,0.5],\"rawText\":\"private\"}"),
                row(2, "{\"suggestedEntries\":[{}],\"approvedEntryIndexes\":[0]}"),
                row(3, "{\"suggestedEntry\":{\"title\":\"private\"}}"),
                row(4, "{\"suggestedEntries\":[]}"), row(5, "invalid-json")));
        var summary = service.reviewSummary(7L);
        assertThat(summary.requests()).extracting(request -> request.analysisId()).containsExactly(1L, 3L);
        assertThat(summary.requests().get(0).approvedEntryIndexes()).containsExactly(0);
        assertThat(summary.requests().get(0).entryCount()).isEqualTo(2);
        assertThat(mapper.writeValueAsString(summary)).doesNotContain("private", "rawText", "title", "imageUrl");
        verify(users).getRequiredUser(7L);
    }

    @Test void summariesCoverOlderHistoryUsingBoundedKeysetPages() {
        var first = LongStream.rangeClosed(1, 64).mapToObj(id -> row(id, "{\"suggestedEntries\":[{}]}")).toList();
        when(repository.findCompletedReviewRows(eq(7L), eq(0L), any())).thenReturn(first);
        when(repository.findCompletedReviewRows(eq(7L), eq(64L), any())).thenReturn(List.of(row(65, "{\"suggestedEntries\":[{}]}")));
        assertThat(service.reviewSummary(7L).requests()).hasSize(65);
        var pages = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findCompletedReviewRows(eq(7L), anyLong(), pages.capture());
        assertThat(pages.getAllValues()).allSatisfy(page -> assertThat(page.getPageSize()).isEqualTo(64));
    }
}
