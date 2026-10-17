package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.ManageTravelPhotoClustersUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.ManageTravelPhotoClustersService;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TransactionalTravelClusterDetails implements ManageTravelPhotoClustersUseCase {
    private final ManageTravelPhotoClustersUseCase delegate;
    private final TravelClusterDetailSynchronizationPort synchronization;
    private final TransactionTemplate reads;

    public TransactionalTravelClusterDetails(TravelClusterDetailStore store,
            TravelClusterDetailSynchronizationPort synchronization, TravelSummaryInvalidationPort summaries,
            PlatformTransactionManager transactions) {
        this.delegate = new ManageTravelPhotoClustersService(store, synchronization, summaries);
        this.synchronization = synchronization;
        this.reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
    }
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Page page(Long owner, Long cluster, Integer page, Integer size, Long focus) {
        // A background projection may need another DB connection: never wait while holding a read transaction.
        synchronization.awaitCurrent(owner);
        return reads.execute(tx -> delegate.page(owner, cluster, page, size, focus));
    }
    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Page changeRepresentative(Long owner, Long cluster, Long media) {
        return delegate.changeRepresentative(owner, cluster, media);
    }
}
