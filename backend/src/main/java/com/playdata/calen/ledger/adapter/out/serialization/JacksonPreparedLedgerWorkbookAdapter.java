package com.playdata.calen.ledger.adapter.out.serialization;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.adapter.support.PreparedLedgerWorkbookFormat.WorkbookPayload;
import com.playdata.calen.ledger.application.port.out.PreparedLedgerWorkbookPort;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JacksonPreparedLedgerWorkbookAdapter implements PreparedLedgerWorkbookPort {
    private final ObjectMapper json;
    @Override public Workbook read(String preparedInput) {
        try {
            var input = json.readValue(preparedInput,WorkbookPayload.class);
            return new Workbook(input.fileName(),input.sheetName(),input.knownChoices() == null ? null : new Choices(input.knownChoices().paymentMethods(),input.knownChoices().categoryDetailsByGroup()),
                    input.sourceReferencesById(),input.sourceReferencesByLocation(),json.writeValueAsString(input.payload()));
        } catch (JsonProcessingException failure) { throw new BadRequestException("Excel 분석 입력을 복구할 수 없습니다."); }
    }
}
