package com.playdata.calen.ledger.adapter.out.embedding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.common.embedding.*;
import com.playdata.calen.ledger.application.port.in.SynchronizeLedgerEmbeddingsUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerEmbeddingIndexPort;
import com.playdata.calen.ledger.application.service.SynchronizeLedgerEmbeddingsService;
import com.playdata.calen.ledger.domain.model.LedgerEmbeddingSyncData.*;
import com.playdata.calen.ledger.embedding.*;
import com.playdata.calen.ledger.embedding.sync.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

class LedgerEmbeddingOutboxIntegrationTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    LedgerEmbeddingJobsAdapter jobs;
    LedgerEmbeddingIndexPort index;
    SynchronizeLedgerEmbeddingsService service;
    final EmbeddingApiClient client=mock(EmbeddingApiClient.class);
    final LedgerEmbeddingDocumentService documents=mock(LedgerEmbeddingDocumentService.class);
    final SynchronizeLedgerEmbeddingsUseCase.Execution execution=new SynchronizeLedgerEmbeddingsUseCase.Execution() {
        public boolean enabled(){return true;} public boolean leaseValid(){return true;}
        public void assertNetworkReady(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
        public void reportFailure(String code,int count,boolean retryable){}
    };

    @BeforeEach void database() {
        var dataSource=new DriverManagerDataSource("jdbc:h2:mem:embedding-boundary-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261006_032__ledger_embedding_sync_jobs.sql")).execute(dataSource);
        jdbc=new JdbcTemplate(dataSource);manager=new DataSourceTransactionManager(dataSource);
        jobs=new LedgerEmbeddingJobsAdapter(new LedgerEmbeddingSyncStore(jdbc,new LedgerEmbeddingSyncProperties(),documents,manager));
        var proxy=new ProxyFactory(new LedgerEmbeddingIndexAdapter(client));
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        index=(LedgerEmbeddingIndexPort)proxy.getProxy();service=new SynchronizeLedgerEmbeddingsService(jobs,index);
        when(documents.findCurrentForSync(anyLong(),any(),anyLong())).thenAnswer(call->{
            long owner=call.getArgument(0);LedgerEmbeddingRecordType type=call.getArgument(1);long source=call.getArgument(2);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.of(new EmbeddingDocument(LedgerEmbeddingPointIds.forRecord(owner,type,source),"synthetic",
                    Map.of("owner_id",owner,"source_id",source,"record_type",type.metadataValue())));
        });
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return null;}).when(client).upsertDocuments(anyList());
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return null;}).when(client).deleteDocuments(anyList());
    }
    @Test void successUsesTheRealShortTransactionProtocolAndOwnerScopedWireDocuments() {
        enqueue(7,123);enqueue(8,123);service.run(4,execution);
        for(long owner:List.of(7L,8L)) {
            assertThat(state(owner,123)).isEqualTo("SUCCEEDED");assertThat(number(owner,123,"applied_revision")).isEqualTo(1);
            assertThat(number(owner,123,"attempts")).isZero();
        }
        @SuppressWarnings("unchecked") var batch=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client).upsertDocuments(batch.capture());
        @SuppressWarnings("unchecked") var docs=(List<EmbeddingDocument>)batch.getValue();
        assertThat(docs).extracting(d->d.metadata().get("owner_id")).containsExactly(7L,8L);
        assertThat(docs.get(0).id()).isNotEqualTo(docs.get(1).id());
    }
    @Test void absentSourceSendsDeleteInsteadOfUpsertAndKeepsStablePointIdentity() {
        enqueue(7,123);doReturn(Optional.empty()).when(documents).findCurrentForSync(7L,LedgerEmbeddingRecordType.LEDGER_ENTRY,123L);
        service.run(4,execution);
        verify(client).deleteDocuments(List.of(UUID.fromString("478b3f6d-a9a4-5cbc-8399-bd378f740e80")));
        verify(client,never()).upsertDocuments(anyList());assertThat(state(7,123)).isEqualTo("SUCCEEDED");
    }
    @Test void editDuringTheNetworkCallCannotAcknowledgeTheNewRevision() {
        enqueue(7,123);
        doAnswer(call->{jdbc.update("update ledger_embedding_sync_jobs set revision=revision+1 where owner_id=7");return null;}).when(client).upsertDocuments(anyList());
        service.run(4,execution);
        assertThat(state(7,123)).isEqualTo("PENDING");assertThat(number(7,123,"revision")).isEqualTo(2);
        assertThat(number(7,123,"applied_revision")).isEqualTo(1);
    }
    @Test void replacedClaimTokenAndExpiredDatabaseLeasePreventLateAcknowledgement() {
        for(boolean replaceToken:List.of(true,false)) {
            long source=replaceToken?123:124;enqueue(7,source);
            doAnswer(call->{
                jdbc.update(replaceToken?"update ledger_embedding_sync_jobs set claim_token='replacement' where owner_id=7 and source_id=?":
                        "update ledger_embedding_sync_jobs set lease_until=timestamp '2000-01-01 00:00:00' where owner_id=7 and source_id=?",source);
                return null;
            }).when(client).upsertDocuments(anyList());
            service.run(4,execution);assertThat(state(7,source)).isEqualTo("PROCESSING");assertThat(number(7,source,"applied_revision")).isZero();
        }
    }
    @Test void retryableProviderFailureWritesOnlySafeCodeAndNeverCompletesTheJob() {
        enqueue(7,123);doThrow(new EmbeddingApiException("private body and credential",429,true)).when(client).upsertDocuments(anyList());
        service.run(4,execution);
        assertThat(state(7,123)).isEqualTo("PENDING");assertThat(number(7,123,"attempts")).isEqualTo(1);
        assertThat(number(7,123,"applied_revision")).isZero();
        assertThat(jdbc.queryForObject("select last_error_code from ledger_embedding_sync_jobs where owner_id=7",String.class)).isEqualTo("HTTP_429");
        assertThat(jobs.claimBatch(4)).isEmpty();
    }
    @Test void nonRetryableFailureIsTerminalButAConcurrentNewRevisionRemainsPendingWithoutOldError() {
        enqueue(7,123);doThrow(new EmbeddingApiException("private",401,false)).when(client).upsertDocuments(anyList());
        service.run(4,execution);assertThat(state(7,123)).isEqualTo("FAILED");
        enqueue(8,123);doAnswer(call->{jdbc.update("update ledger_embedding_sync_jobs set revision=2 where owner_id=8");throw new EmbeddingApiException("private",401,false);})
                .when(client).upsertDocuments(anyList());
        service.run(4,execution);assertThat(state(8,123)).isEqualTo("PENDING");assertThat(number(8,123,"attempts")).isZero();
        assertThat(jdbc.queryForObject("select last_error_code from ledger_embedding_sync_jobs where owner_id=8",String.class)).isNull();
    }
    @Test void staleOwnerTokenAndRevisionCannotCompleteAnotherClaim() {
        enqueue(7,123);var claim=jobs.claimBatch(4).get(0);
        jobs.succeeded(new Claim(new Key(8,"ledger_entry",123),claim.revision(),claim.token(),claim.attempts()));
        jobs.succeeded(new Claim(claim.key(),claim.revision(),"incorrect-token",claim.attempts()));
        jobs.succeeded(new Claim(claim.key(),99,claim.token(),claim.attempts()));
        assertThat(state(7,123)).isEqualTo("PROCESSING");assertThat(number(7,123,"applied_revision")).isZero();
        jobs.succeeded(claim);assertThat(state(7,123)).isEqualTo("SUCCEEDED");
    }
    @Test void claimTransactionStillCommitsIndependentlyOfAnOuterRollback() {
        enqueue(7,123);new TransactionTemplate(manager).executeWithoutResult(tx->{jobs.claimBatch(4);tx.setRollbackOnly();});
        assertThat(state(7,123)).isEqualTo("PROCESSING");
    }
    @Test void directIndexPortSuspendsAndThenRestoresTheCallersTransaction() {
        enqueue(7,123);
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            jdbc.update("update ledger_embedding_sync_jobs set revision=2 where owner_id=7");
            index.upsert(List.of(new Document(new UUID(0,1),"synthetic",Map.of("owner_id",7L))));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(number(7,123,"revision")).isEqualTo(2);tx.setRollbackOnly();
        });
        assertThat(number(7,123,"revision")).isEqualTo(1);
    }
    @Test void expiredClaimWithNewRevisionResetsAttemptsAndCanBeRecovered() {
        enqueue(7,123);jobs.claimBatch(4);
        jdbc.update("update ledger_embedding_sync_jobs set revision=2,attempts=8,lease_until=timestamp '2000-01-01 00:00:00' where owner_id=7");
        jobs.recoverExpired();
        assertThat(state(7,123)).isEqualTo("PENDING");assertThat(number(7,123,"attempts")).isZero();
        assertThat(jdbc.queryForObject("select last_error_code from ledger_embedding_sync_jobs where owner_id=7",String.class)).isEqualTo("LEASE_EXPIRED");
    }
    @Test void simultaneousWorkersCannotClaimTheSameRevisionTwiceEvenAfterBothReadTheCandidate() throws Exception {
        enqueue(7,123);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        var gated=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                var rows=super.query(sql,mapper,args);
                if(sql.startsWith("select owner_id, record_type, source_id, revision, state")) {
                    try{barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);}
                    catch(Exception failure){throw new IllegalStateException("synthetic claim barrier",failure);}
                }
                return rows;
            }
        };
        var concurrent=new LedgerEmbeddingJobsAdapter(new LedgerEmbeddingSyncStore(gated,new LedgerEmbeddingSyncProperties(),documents,manager));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->concurrent.claimBatch(4));var second=pool.submit(()->concurrent.claimBatch(4));
            var a=first.get(10,java.util.concurrent.TimeUnit.SECONDS);var b=second.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.size()+b.size()).isEqualTo(1);assertThat(state(7,123)).isEqualTo("PROCESSING");
            assertThat(number(7,123,"attempts")).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    void enqueue(long owner,long source) {
        jdbc.update("insert into ledger_embedding_sync_jobs(owner_id,record_type,source_id,next_attempt_at,created_at,updated_at) values(?, 'ledger_entry', ?, timestamp '2000-01-01 00:00:00',current_timestamp(6),current_timestamp(6))",owner,source);
    }
    String state(long owner,long source){return jdbc.queryForObject("select state from ledger_embedding_sync_jobs where owner_id=? and source_id=?",String.class,owner,source);}
    long number(long owner,long source,String column) {
        if(!Set.of("applied_revision","revision","attempts").contains(column))throw new IllegalArgumentException("test column");
        return jdbc.queryForObject("select "+column+" from ledger_embedding_sync_jobs where owner_id=? and source_id=?",Long.class,owner,source);
    }
}
