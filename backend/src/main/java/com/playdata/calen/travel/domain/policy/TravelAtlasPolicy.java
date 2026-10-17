package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.travel.domain.model.TravelAtlasData.*;
import java.util.*;

public final class TravelAtlasPolicy {
    private TravelAtlasPolicy() {}
    public static List<Access> publicFirst(List<Plan> published, List<Access> received) {
        var byId = new LinkedHashMap<Long, Access>();
        published.forEach(plan -> byId.put(plan.id(), new Access(plan, null, null)));
        received.forEach(access -> byId.putIfAbsent(access.plan().id(), access));
        return List.copyOf(byId.values());
    }
    public static Sharing sharing(Plan plan, Access share) {
        boolean published = plan != null && Boolean.TRUE.equals(plan.publicShared());
        var owner = plan == null ? null : plan.owner();
        return new Sharing(owner == null ? "" : owner.login(), owner == null ? "" : owner.display(),
                plan == null ? null : published ? plan.publicAt() : share == null ? null : share.sharedAt(),
                published || share == null ? "PUBLIC" : "RESTRICTED", share == null ? null : share.share());
    }
    public static int page(Integer value) { return value == null ? 0 : Math.max(value, 0); }
    public static int size(Integer value) { return value == null ? 10 : Math.max(1, Math.min(value, 20)); }
}
