package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.ManageOwnedTravelMediaService;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Media;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class TransactionalOwnedTravelMedia implements ManageOwnedTravelMediaUseCase {
    private final ManageOwnedTravelMediaUseCase delegate;
    public TransactionalOwnedTravelMedia(TravelMediaStore media, TravelMediaStoragePort storage, OwnedTravelMediaCachePort cache,
            TravelSummaryInvalidationPort summaries, TravelClusterRefreshPort clusters) {
        delegate = new ManageOwnedTravelMediaService(media, storage, cache, summaries, clusters);
    }

    @Override
    public Prepared prepare(Long owner, Long record, TravelRecordType type, List<Candidate> files) {
        return delegate.prepare(owner, record, type, files);
    }

    @Override
    @Transactional
    public List<Media> complete(Long owner, Long record, TravelRecordType type, TravelMediaType requested,
            String caption, List<Completed> files) {
        return delegate.complete(owner, record, type, requested, caption, files);
    }

    @Override
    public Download download(Long owner, Long id) {
        return delegate.download(owner, id);
    }

    @Override
    @Transactional
    public void delete(Long owner, Long id) {
        delegate.delete(owner, id);
    }
}
