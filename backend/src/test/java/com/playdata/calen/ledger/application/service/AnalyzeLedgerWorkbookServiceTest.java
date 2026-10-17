package com.playdata.calen.ledger.application.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnalyzeLedgerWorkbookServiceTest {
    private final PreparedLedgerWorkbookPort inputs = mock(PreparedLedgerWorkbookPort.class);
    private final LedgerWorkbookExtractionPort remote = mock(LedgerWorkbookExtractionPort.class);
    private final AnalyzeLedgerWorkbookService service = new AnalyzeLedgerWorkbookService(inputs,remote);
    @Test void onlyProviderIndependentValuesCrossThePortAndTheResultRemainsAPreview() {
        var source = new SourceReference("source","Sheet",7);
        when(inputs.read("persisted JSON")).thenReturn(new Workbook("book.xlsx","Sheet",new Choices(Set.of(),Map.of()),Map.of("source",source),Map.of(),"workbook payload"));
        when(remote.extract("workbook payload")).thenReturn(new Extraction(List.of(new Candidate("source","forged",99,"2026-10-16","","Title","memo",new Amount(BigDecimal.TEN,null),"EXPENSE","","","",List.of())),List.of()));
        var result = service.analyze("persisted JSON");
        assertThat(result.rows().get(0).sourceRowNumber()).isEqualTo(7); assertThat(result.readyRowCount()).isEqualTo(1);
        var order = inOrder(inputs,remote); order.verify(inputs).read("persisted JSON"); order.verify(remote).extract("workbook payload");
        verifyNoMoreInteractions(inputs,remote);
    }
    @Test void corruptPersistedInputStopsBeforeCallingTheProvider() {
        when(inputs.read("bad")).thenThrow(new BadRequestException("bad input"));
        assertThatThrownBy(() -> service.analyze("bad")).hasMessage("bad input"); verifyNoInteractions(remote);
    }
}
