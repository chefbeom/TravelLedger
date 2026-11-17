package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.domain.TravelPlan;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TravelPlanStorageProvenanceTest {
    private final TravelPlanStorageProvenance provenance = new TravelPlanStorageProvenance(new ObjectMapper());

    @Test void transferRecordsEveryFormerOwnerWithoutRewritingTheObjectPath() {
        TravelPlan plan = plan(10, 1);
        String file = UUID.randomUUID() + "-photo.jpg";
        String oldPath = "travel-media/1/10/20/" + file;
        assertThat(provenance.requirePath(plan, 20L, false, oldPath, file, "image/jpeg", 3L)).isEqualTo(1L);
        provenance.rememberCurrentOwner(plan);
        plan.setOwner(user(2));
        assertThat(provenance.requirePath(plan, 20L, false, oldPath, file, "image/jpeg", 3L)).isEqualTo(1L);
        provenance.rememberCurrentOwner(plan);
        plan.setOwner(user(3));
        assertThat(provenance.allowedOrigins(plan)).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(provenance.requirePath(plan, 20L, false, oldPath, file, "image/jpeg", 3L)).isEqualTo(1L);
        String route = "2/10/routes/30/" + UUID.randomUUID() + "-track.gpx";
        assertThat(provenance.requirePath(plan, 30L, true, route, null, "application/gpx+xml", 9L)).isEqualTo(2L);
    }

    @Test void unrelatedOrCorruptedPathsAndHistoryAreRejected() {
        TravelPlan plan = plan(10, 1);
        String file = UUID.randomUUID() + "-photo.jpg";
        assertThatThrownBy(() -> provenance.requirePath(plan, 20L, false, "2/10/20/" + file, file, "image/jpeg", 3L))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> provenance.requirePath(plan, 20L, false, "1/10/20/../" + file, file, "image/jpeg", 3L))
                .isInstanceOf(BadRequestException.class);
        for (String bad : new String[] {"{}", "[1,1]", "[0]", "[\"1\"]", "not-json"}) {
            plan.setStorageOwnerHistoryJson(bad);
            assertThatThrownBy(() -> provenance.allowedOrigins(plan)).isInstanceOf(BadRequestException.class);
        }
    }

    private TravelPlan plan(long id, long owner) {
        TravelPlan plan = new TravelPlan(); plan.setId(id); plan.setOwner(user(owner)); return plan;
    }
    private AppUser user(long id) { AppUser user = new AppUser(); user.setId(id); return user; }
}
