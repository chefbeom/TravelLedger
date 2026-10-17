package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.*;
import com.playdata.calen.travel.dto.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TravelClusterDetailMapperTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void keepsAllTwentyEightMediaFieldsAndExactJsonForOwnedAndCustomSharedUrls() throws Exception {
        for (TravelRecordType recordType : TravelRecordType.values()) for (TravelMediaType mediaType : TravelMediaType.values()) {
            for (String color : Arrays.asList(null,"","#abcdef"," #12ab34 ")) for (Boolean override : Arrays.asList(null,false,true)) {
                var asset = asset(recordType, mediaType, color, override);
                var value = TravelClusterDetailMapper.media(asset);
                for (String url : List.of("/api/travel/media/44/content","/api/travel/public/map-shares/test-token/media/44/content")) {
                    var actual = TravelClusterDetailMapper.response(value,url);
                    assertThat(actual).isEqualTo(previous(asset,url));
                    assertThat(json.writeValueAsBytes(actual)).isEqualTo(json.writeValueAsBytes(previous(asset,url)));
                }
                assertThat(TravelClusterDetailMapper.owned(value).contentUrl()).isEqualTo("/api/travel/media/44/content");
            }
        }
    }
    @Test void nullOptionalFieldsRemainNullAndNullRepresentativeRemainsNull() throws Exception {
        var asset=asset(null,null,null,null); var record=asset.getRecord();
        asset.setCaption(null); asset.setUploadedAt(null); asset.setGpsLatitude(null); asset.setGpsLongitude(null);
        record.setExpenseDate(null); record.setExpenseTime(null); record.setTitle(null); record.setAmount(null);
        record.setCurrencyCode(null); record.setAmountKrw(null); record.setCountry(null); record.setRegion(null); record.setPlaceName(null);
        record.setLatitude(null); record.setLongitude(null);
        assertThat(json.writeValueAsBytes(TravelClusterDetailMapper.response(TravelClusterDetailMapper.media(asset),"custom")))
                .isEqualTo(json.writeValueAsBytes(previous(asset,"custom")));
        assertThat(TravelClusterDetailMapper.owned(null)).isNull();
    }
    @Test void pagePreservesHeaderCountsCoordinatesAndRepresentativeEvenWhenNotInThePage() {
        var rep=TravelClusterDetailMapper.media(asset(TravelRecordType.MEMORY,TravelMediaType.PHOTO,"#abcdef",true));
        var detail=new Detail(33L,44L,22L,new BigDecimal("37.5000000"),new BigDecimal("127.0000000"),80,30,new BigDecimal("4.20"),true,rep,List.of());
        var response=TravelClusterDetailMapper.response(new Page(detail,List.of(),5,12,80,true));
        assertThat(response).isEqualTo(new TravelMyMapPhotoClusterPageResponse(33L,44L,22L,detail.latitude(),detail.longitude(),
                80,30,new BigDecimal("4.20"),true,TravelClusterDetailMapper.owned(rep),List.of(),5,12,80,true));
    }
    @Test void malformedPlanColorKeepsTheExistingBadRequestBehavior() {
        assertThatThrownBy(()->TravelClusterDetailMapper.media(asset(null,null,"blue",false)))
                .hasMessage("Travel color must use #RRGGBB format.");
    }
    static TravelMediaAsset asset(TravelRecordType recordType,TravelMediaType mediaType,String color,Boolean override) {
        var user=new AppUser(); user.setId(7L); user.setDisplayName("사진 주인");
        var plan=new TravelPlan(); plan.setId(11L); plan.setOwner(user); plan.setName("여행"); plan.setColorHex(color);
        var record=new TravelExpenseRecord(); record.setId(22L); record.setPlan(plan); record.setRecordType(recordType);
        record.setExpenseDate(LocalDate.of(2026,10,15)); record.setExpenseTime(LocalTime.of(13,42));
        record.setTitle("장소"); record.setAmount(new BigDecimal("1200.50")); record.setCurrencyCode("JPY"); record.setAmountKrw(new BigDecimal("11200.70"));
        record.setCountry("일본"); record.setRegion("교토"); record.setPlaceName("교토역"); record.setLatitude(new BigDecimal("35.1234567")); record.setLongitude(new BigDecimal("135.1234567"));
        var media=new TravelMediaAsset(); media.setId(44L); media.setPlan(plan); media.setRecord(record); media.setUploadedBy(user);
        media.setMediaType(mediaType); media.setOriginalFileName("사진.jpg"); media.setContentType("image/jpeg"); media.setFileSize(54321L);
        media.setCaption("메모"); media.setUploadedAt(LocalDateTime.of(2026,10,16,9,30)); media.setGpsLatitude(new BigDecimal("35.1111111")); media.setGpsLongitude(new BigDecimal("135.2222222"));
        media.setRepresentativeOverride(override); return media;
    }

    // Frozen original mapping, independent of the new core media record.
    private TravelMediaResponse previous(TravelMediaAsset mediaAsset, String contentUrl) {
        TravelExpenseRecord record = mediaAsset.getRecord();
        TravelPlan plan = mediaAsset.getPlan();
        String colorHex = plan.getColorHex() == null || plan.getColorHex().isBlank() ? "#3182F6" : plan.getColorHex().trim().toUpperCase();
        return new TravelMediaResponse(
                mediaAsset.getId(),
                plan.getId(),
                plan.getName(),
                colorHex,
                record.getId(),
                record.getRecordType(),
                mediaAsset.getMediaType(),
                mediaAsset.getOriginalFileName(),
                mediaAsset.getContentType(),
                mediaAsset.getFileSize(),
                mediaAsset.getCaption(),
                mediaAsset.getUploadedBy().getDisplayName(),
                mediaAsset.getUploadedAt(),
                contentUrl,
                record.getExpenseDate(),
                record.getExpenseTime(),
                record.getTitle(),
                record.getAmount(),
                record.getCurrencyCode(),
                record.getAmountKrw(),
                record.getCountry(),
                record.getRegion(),
                record.getPlaceName(),
                record.getLatitude(),
                record.getLongitude(),
                mediaAsset.getGpsLatitude(),
                mediaAsset.getGpsLongitude(),
                Boolean.TRUE.equals(mediaAsset.getRepresentativeOverride())
        );
    }
}
