package com.playdata.calen.ledger.adapter.support;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.*;
import com.playdata.calen.ledger.dto.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LedgerWorkbookPreviewMapperTest {
    @Test void previewKeepsTheExistingJsonFieldOrderNullHeaderAndDecimalScale() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var amount = new BigDecimal("4.5E+3"); var date = LocalDate.of(2026,10,16); var time = LocalTime.of(13,5);
        var row = new PreviewRow(1,"Sheet",7,date,time,"Coffee","memo",amount,EntryType.EXPENSE,"Card","Food","Cafe",true,List.of());
        var preview = new Preview("book.xlsx","Sheet",1,1,0,List.of("note"),List.of(row));
        var expected = new LedgerExcelPreviewResponse("book.xlsx","Sheet",null,1,1,0,List.of("note"),List.of(
                new LedgerExcelPreviewRowResponse(1,"Sheet",7,date,time,"Coffee","memo",amount,EntryType.EXPENSE,"Card","Food","Cafe",true,List.of())));
        assertThat(json.writeValueAsBytes(LedgerWorkbookPreviewMapper.response(preview))).isEqualTo(json.writeValueAsBytes(expected));
    }
}
