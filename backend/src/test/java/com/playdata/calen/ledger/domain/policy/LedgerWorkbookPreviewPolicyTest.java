package com.playdata.calen.ledger.domain.policy;

import static org.assertj.core.api.Assertions.*;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

class LedgerWorkbookPreviewPolicyTest {
    private final SourceReference source = new SourceReference("source-1","Sheet",7);
    private final Choices choices = new Choices(Set.of("Card"),Map.of("Food",Set.of("Cafe")));
    private final Workbook workbook = new Workbook("book.xlsx","Sheet",choices,Map.of("source-1",source),Map.of("Sheet\u00007",source),"{}");
    private Candidate candidate(String id,String sheet,int row,Amount amount,String payment,String group,String detail) {
        return new Candidate(id,sheet,row,"2026-10-16","13:05:59"," Coffee "," memo ",amount,"EXPENSE",payment,group,detail,List.of());
    }
    private Candidate valid(String id) { return candidate(id,"",-1,new Amount(new BigDecimal("4500"),null),"Card","Food","Cafe"); }
    private Preview preview(Candidate... rows) { return LedgerWorkbookPreviewPolicy.preview(workbook,new Extraction(List.of(rows),List.of("provider note"))); }

    @Test void explicitUnknownSourceCannotFallBackToAnOtherwiseValidLocationAndDuplicatesAreRemoved() {
        var forged = candidate("unknown","Sheet",7,new Amount(BigDecimal.ONE,null),"","","");
        var byLocation = candidate("","Sheet",7,new Amount(BigDecimal.ONE,null),"","","");
        var result = preview(forged,valid("source-1"),byLocation);
        assertThat(result.rows()).hasSize(1); assertThat(result.rows().get(0).sourceRowNumber()).isEqualTo(7);
        assertThat(result.notes()).containsExactly("AI가 원본 Excel 행과 연결된 결과만 미리보기에 표시했습니다. 선택한 항목만 최종 저장됩니다.",
                "원본 행을 확인할 수 없는 AI 결과 1건은 제외했습니다.","같은 원본 행을 중복 참조한 AI 결과 1건은 제외했습니다.","provider note");
    }
    @Test void missingSourceIdMayUseTheOriginalSheetAndRowAndCannotOverrideItsMetadata() {
        var result = preview(candidate(" "," Sheet ",7,new Amount(BigDecimal.TEN,null),"Card","Food","Cafe")).rows().get(0);
        assertThat(result.sourceSheetName()).isEqualTo("Sheet"); assertThat(result.sourceRowNumber()).isEqualTo(7);
        assertThat(result.title()).isEqualTo("Coffee"); assertThat(result.memo()).isEqualTo("memo");
        assertThat(result.entryTime()).isEqualTo(LocalTime.of(13,5)); assertThat(result.ready()).isTrue();
    }
    @Test void noOriginalSourcesIsAnErrorRatherThanInventedLedgerRows() {
        assertThatThrownBy(() -> preview(valid("unknown"))).isInstanceOf(BadRequestException.class).hasMessageContaining("원본 Excel 행");
    }
    @Test void unknownPaymentAndCategoryNamesAreClearedAndRemainUnready() {
        var row = preview(candidate("source-1","",-1,new Amount(BigDecimal.ONE,null),"Other card","Other group","Cafe")).rows().get(0);
        assertThat(row.paymentMethodName()).isEmpty(); assertThat(row.categoryGroupName()).isEmpty(); assertThat(row.categoryDetailName()).isEmpty();
        assertThat(row.issues()).containsExactly("등록된 결제수단 확인 필요","등록된 대분류 확인 필요"); assertThat(row.ready()).isFalse();
    }
    @Test void detailMustBelongToItsKnownParentOrBeCleared() {
        var unknown = preview(candidate("source-1","",-1,new Amount(BigDecimal.ONE,null),"","Food","Other")).rows().get(0);
        assertThat(unknown.categoryGroupName()).isEqualTo("Food"); assertThat(unknown.categoryDetailName()).isEmpty();
        assertThat(unknown.issues()).containsExactly("등록된 분류 확인 필요");
        var orphan = preview(candidate("source-1","",-1,new Amount(BigDecimal.ONE,null),"","","Cafe")).rows().get(0);
        assertThat(orphan.categoryDetailName()).isEmpty(); assertThat(orphan.issues()).containsExactly("대분류 없는 분류 확인 필요");
    }
    @Test void invalidRequiredValuesKeepTheirLegacyIssueOrderAndDoNotValidateTimeAsARequiredField() {
        var row = new Candidate("source-1","",-1,"invalid","bad"," ","",null,"unknown","","","",List.of("provider issue"));
        var result = preview(row).rows().get(0);
        assertThat(result.issues()).containsExactly("거래일 확인 필요","내용 확인 필요","금액 확인 필요","수입/지출 구분 확인 필요","provider issue");
        assertThat(result.title()).isEqualTo("확인 필요"); assertThat(result.entryType()).isEqualTo(EntryType.EXPENSE); assertThat(result.entryTime()).isNull();
    }
    @ParameterizedTest @CsvSource({"2026-10-16,2026-10-16","2026-1-6,2026-01-06","2026/10/16,2026-10-16","2026.10.16,2026-10-16","20261016,2026-10-16"})
    void supportedDateFormatsRemainReadable(String date,String expected) {
        var r = valid("source-1");
        var row = new Candidate(r.sourceId(),r.sourceSheetName(),r.sourceRowNumber(),date,"",r.title(),r.memo(),r.amount(),r.entryType(),r.paymentMethodName(),r.categoryGroupName(),r.categoryDetailName(),r.issues());
        assertThat(preview(row).rows().get(0).entryDate()).isEqualTo(LocalDate.parse(expected));
    }
    @Test void numberAndTextNegativeAmountSemanticsAndRoundingArePreserved() {
        var number = preview(candidate("source-1","",-1,new Amount(new BigDecimal("-4500"),null),"","","")).rows().get(0);
        assertThat(number.amount()).isEqualByComparingTo("-4500"); assertThat(number.issues()).containsExactly("금액 확인 필요");
        var text = preview(candidate("source-1","",-1,new Amount(null,"₩ -4,500"),"","","")).rows().get(0);
        assertThat(text.amount()).isEqualByComparingTo("4500"); assertThat(text.ready()).isTrue();
        assertThat(preview(candidate("source-1","",-1,new Amount(new BigDecimal("1.005"),null),"","","")).rows().get(0).amount()).isEqualTo(new BigDecimal("1.01"));
    }
    @ParameterizedTest @ValueSource(strings={"입금","수입","income"})
    void knownIncomeLabelsRemainIncome(String type) {
        var r = valid("source-1");
        var row = new Candidate(r.sourceId(),r.sourceSheetName(),r.sourceRowNumber(),r.entryDate(),r.entryTime(),r.title(),r.memo(),r.amount(),type,r.paymentMethodName(),r.categoryGroupName(),r.categoryDetailName(),r.issues());
        assertThat(preview(row).rows().get(0).entryType()).isEqualTo(EntryType.INCOME);
    }
    @Test void acceptedRowsAreLimitedTo500AfterFilteringRatherThanTruncatingRawCandidatesFirst() {
        Map<String,SourceReference> sources = new LinkedHashMap<>(); List<Candidate> rows = new ArrayList<>(); rows.add(valid("unknown"));
        for (int i=1;i<=501;i++) { sources.put("r"+i,new SourceReference("r"+i,"Sheet",i)); rows.add(valid("r"+i)); }
        var input = new Workbook("book.xlsx","Sheet",choices,sources,Map.of(),"{}");
        var result = LedgerWorkbookPreviewPolicy.preview(input,new Extraction(rows,List.of()));
        assertThat(result.detectedRowCount()).isEqualTo(500); assertThat(result.readyRowCount()).isEqualTo(500); assertThat(result.skippedRowCount()).isZero();
        assertThat(result.rows().get(499).previewIndex()).isEqualTo(500); assertThat(result.notes()).anyMatch(note -> note.contains("없는 AI 결과 1건"));
    }
}
