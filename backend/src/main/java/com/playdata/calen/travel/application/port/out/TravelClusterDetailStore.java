package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelClusterDetailData.*;
import java.util.*;

public interface TravelClusterDetailStore {
    void requireOwner(Long owner);
    Page page(Long owner, Long cluster, int page, int size, Long focus);
    Optional<Detail> detail(Long owner, Long cluster);
    /** IDs have already been checked against the owner's stored cluster membership. */
    void changeRepresentative(List<Long> memberIds, Long selected);
    Optional<Long> clusterForMedia(Long owner, Long media);
}
