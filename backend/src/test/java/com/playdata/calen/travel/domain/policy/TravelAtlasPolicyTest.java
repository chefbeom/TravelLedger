package com.playdata.calen.travel.domain.policy;

import static org.assertj.core.api.Assertions.*;

import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.domain.model.TravelAtlasData.*;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelAtlasPolicyTest {
    @Test void publishedOrderWinsOverReceivedDuplicatesAndTheFirstPrivateShareWins() {
        var first = plan(1L, true); var second = plan(2L, true); var third = plan(3L, false);
        var at = LocalDateTime.of(2026, 10, 16, 1, 2);
        var result = TravelAtlasPolicy.publicFirst(List.of(second, first), List.of(new Access(first, 10L, at),
                new Access(third, 11L, at), new Access(third, 12L, at.plusMinutes(1))));
        assertThat(result).extracting(a -> a.plan().id()).containsExactly(2L, 1L, 3L);
        assertThat(result.get(0).share()).isNull(); assertThat(result.get(1).share()).isNull(); assertThat(result.get(2).share()).isEqualTo(11L);
        assertThat(TravelAtlasPolicy.sharing(first, new Access(first, 10L, at)).visibility()).isEqualTo("PUBLIC");
        assertThat(TravelAtlasPolicy.sharing(third, result.get(2)).at()).isEqualTo(at);
        assertThat(TravelAtlasPolicy.sharing(null, null).visibility()).isEqualTo("PUBLIC");
    }
    @Test void feedPagingKeepsNullNegativeAndBoundaryRulesSeparateFromClusterPaging() {
        assertThat(TravelAtlasPolicy.page(null)).isZero(); assertThat(TravelAtlasPolicy.page(-3)).isZero();
        assertThat(TravelAtlasPolicy.size(null)).isEqualTo(10); assertThat(TravelAtlasPolicy.size(0)).isEqualTo(1);
        assertThat(TravelAtlasPolicy.size(100)).isEqualTo(20); assertThat(TravelAtlasPolicy.page(Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
    }
    private Plan plan(Long id, boolean published) {
        return new Plan(id, "Trip", null, null, null, TravelPlanStatus.COMPLETED, null, new Owner("owner", "Owner"), published, null);
    }
}
