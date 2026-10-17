package com.playdata.calen.ledger.config;

import com.playdata.calen.ledger.application.port.in.SynchronizeLedgerEmbeddingsUseCase;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.application.service.SynchronizeLedgerEmbeddingsService;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class LedgerEmbeddingUseCaseConfiguration {
    @Bean public SynchronizeLedgerEmbeddingsUseCase synchronizeLedgerEmbeddingsUseCase(
            LedgerEmbeddingJobsPort jobs, LedgerEmbeddingIndexPort index) {
        return new SynchronizeLedgerEmbeddingsService(jobs,index);
    }
}
