package com.playdata.calen.travel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache;
import com.playdata.calen.travel.adapter.support.TravelRouteMapper;
import com.playdata.calen.travel.application.port.in.WriteTravelRouteUseCase;
import com.playdata.calen.travel.application.port.out.TravelRouteStore;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.dto.*;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@SpringBootTest(properties={
    "app.seed.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:travel-route-refactoring;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","app.schema.legacy-updaters.enabled=false",
    "minio.endpoint=","minio.access-key=","minio.secret-key=","app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TravelRouteRefactoringIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelRouteSegmentRepository routes;
    @Autowired WriteTravelRouteUseCase writes;
    @Autowired com.playdata.calen.travel.application.port.in.ManageTravelRouteFilesUseCase routeFiles;
    @Autowired TravelRouteStore store;
    @Autowired TravelService legacy;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelDriveLinkService drive;
    @MockitoBean TravelClusterRefreshCoordinator coordinator;
    record Fixture(long owner,long plan) {}

    @Test void existingHttpRoutesCreateUpdateDeleteWithTheSameJsonAndCommitEviction() throws Exception {
        var f=fixture();
        var created=mvc.perform(post("/api/travel/plans/{id}/routes",f.plan()).with(user(principal(f))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(TravelRouteSourceType.MANUAL,null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planId").value(f.plan()))
                .andExpect(jsonPath("$.lineColorHex").value("#ABCDEF")).andExpect(jsonPath("$.points.length()").value(2))
                .andReturn();
        long id=json.readTree(created.getResponse().getContentAsByteArray()).get("id").asLong();
        var saved=routes.findByIdAndPlanOwnerId(id,f.owner()).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("route");assertThat(saved.getDistanceKm()).isEqualByComparingTo("1.235");
        assertThat(saved.getRoutePathJson()).startsWith("[");verifyEviction(f,2);clearInvocations(redis);
        mvc.perform(put("/api/travel/routes/{id}",id).with(user(principal(f))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(TravelRouteSourceType.GPX,"#123456"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lineColorHex").value("#123456"))
                .andExpect(jsonPath("$.sourceType").value("GPX"));
        assertThat(routes.findById(id).orElseThrow().getRoutePathJson()).startsWith("{\"format\":\"POLYLINE6\"");
        verifyEviction(f,2);clearInvocations(redis);
        mvc.perform(delete("/api/travel/routes/{id}",id).with(user(principal(f))).with(csrf())).andExpect(status().isOk());
        assertThat(routes.findById(id)).isEmpty();verifyEviction(f,2);
    }
    @Test void foreignOwnerCannotCreateUpdateOrDeleteAndNoFilesOrCachesAreTouched() throws Exception {
        var owner=fixture();var other=fixture();long id=create(owner);clearInvocations(redis,storage,drive);
        mvc.perform(post("/api/travel/plans/{id}/routes",owner.plan()).with(user(principal(other))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(TravelRouteSourceType.MANUAL,null)))).andExpect(status().isNotFound());
        mvc.perform(put("/api/travel/routes/{id}",id).with(user(principal(other))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(TravelRouteSourceType.MANUAL,null)))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/travel/routes/{id}",id).with(user(principal(other))).with(csrf())).andExpect(status().isNotFound());
        assertThat(routes.findById(id)).isPresent();verifyNoInteractions(redis,storage,drive);
    }
    @Test void authenticationCsrfAndBeanValidationRemainOnTheNewController() throws Exception {
        var f=fixture();long id=create(f);clearInvocations(redis,storage,drive);
        mvc.perform(post("/api/travel/plans/{id}/routes",f.plan()).with(user(principal(f))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/travel/routes/{id}",id).with(user(principal(f)))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/travel/routes/{id}",id).with(csrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(redis,storage,drive);
    }
    @Test void updateRollbackRestoresTheRouteAndDoesNotRunCommitEviction() {
        var f=fixture();long id=create(f);String original=routes.findById(id).orElseThrow().getRoutePathJson();clearInvocations(redis);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            writes.update(f.owner(),id,TravelRouteMapper.draft(request(TravelRouteSourceType.GPX,"#123456")));
            assertThat(routes.findById(id).orElseThrow().getSourceType()).isEqualTo(TravelRouteSourceType.GPX);
            verifyEviction(f,1);tx.setRollbackOnly();
        });
        assertThat(routes.findById(id).orElseThrow().getRoutePathJson()).isEqualTo(original);verifyEviction(f,1);
    }
    @Test void responseMappingFailureAfterSaveRollsBackTheInsertedRoute() {
        var f=fixture();
        new TransactionTemplate(transactions).executeWithoutResult(tx->plans.findById(f.plan()).orElseThrow().setColorHex("broken"));
        long count=routes.count();clearInvocations(redis);
        assertThatThrownBy(()->writes.create(f.owner(),f.plan(),TravelRouteMapper.draft(request(TravelRouteSourceType.MANUAL,"#123456"))))
                .hasMessage("Travel color must use #RRGGBB format.");
        assertThat(routes.count()).isEqualTo(count);verifyNoInteractions(redis);
    }
    @Test void editKeepsExistingGpxMetadataAndLegacyEntryPointsDelegateToTheSameWriteFlow() {
        var f=fixture();long id=create(f);metadata(id);
        var response=legacy.updateRouteSegment(f.owner(),id,request(TravelRouteSourceType.MANUAL,"#123456"));
        assertThat(response.id()).isEqualTo(id);assertThat(response.gpxFileNames()).containsExactly("original.gpx");
        assertThat(routes.findById(id).orElseThrow().getGpxFilesJson()).contains("test-only/original.gpx");
        verify(storage,never()).deleteQuietly(any());verify(drive,never()).removeRouteGpxLinks(any());
        legacy.deleteRouteSegment(f.owner(),id);assertThat(routes.findById(id)).isEmpty();
    }
    @Test void individualDeleteKeepsTheExistingImmediateFileOrderEvenIfTheDatabaseTransactionRollsBack() {
        var f=fixture();long id=create(f);metadata(id);clearInvocations(redis,drive,storage);
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(routes.findById(id)).isPresent();return null;
        }).when(storage).deleteQuietly("test-only/original.gpx");
        new TransactionTemplate(transactions).executeWithoutResult(tx->{writes.delete(f.owner(),id);tx.setRollbackOnly();});
        assertThat(routes.findById(id)).isPresent();
        var order=inOrder(drive,storage);order.verify(drive).removeRouteGpxLinks(any());order.verify(storage).deleteQuietly("test-only/original.gpx");
        verifyEviction(f,1);
    }
    @Test void failedImmediateFileCleanupDoesNotDeleteTheRowOrEvictCaches() {
        var f=fixture();long id=create(f);metadata(id);clearInvocations(redis);
        doThrow(new IllegalStateException("synthetic storage failure")).when(storage).deleteQuietly("test-only/original.gpx");
        assertThatThrownBy(()->writes.delete(f.owner(),id)).hasMessage("synthetic storage failure");
        assertThat(routes.findById(id)).isPresent();verifyNoInteractions(redis);
    }
    @Test void legacyMultipartReplacementStillRemovesOldLinksAndFilesBeforeStoringNewOnes() {
        var f=fixture();long id=create(f);metadata(id);var upload=new MockMultipartFile("files","new.gpx","application/gpx+xml","synthetic".getBytes(StandardCharsets.UTF_8));
        when(storage.storeRouteGpx(eq(f.owner()),eq(f.plan()),eq(id),any())).thenReturn(stored("new.gpx","test-only/new.gpx"));
        when(storage.loadAsResource("test-only/new.gpx")).thenReturn(gpx());
        var response=legacy.uploadRouteGpxFiles(f.owner(),id,List.of(upload));
        assertThat(response.gpxFileNames()).containsExactly("new.gpx");assertThat(response.points().get(0).latitude()).isEqualByComparingTo("37.1");
        var order=inOrder(drive,storage);order.verify(drive).removeRouteGpxLinks(any());order.verify(storage).deleteQuietly("test-only/original.gpx");
        order.verify(storage).storeRouteGpx(eq(f.owner()),eq(f.plan()),eq(id),any());order.verify(drive).replaceRouteGpxLinks(any(),anyList());
    }
    @Test void legacyPresignedCompletionStillValidatesNewObjectsBeforeDeletingOldOnes() {
        var f=fixture();long id=create(f);metadata(id);when(storage.supportsPresignedUpload()).thenReturn(true);
        when(storage.completePresignedRouteGpxUpload(eq(f.owner()),eq(f.plan()),eq(id),any())).thenReturn(stored("new.gpx","test-only/new.gpx"));
        when(storage.loadAsResource("test-only/new.gpx")).thenReturn(gpx());
        var response=legacy.completeRouteGpxUpload(f.owner(),id,new TravelRouteGpxUploadCompleteRequest(List.of(new TravelRouteGpxUploadCompleteFileRequest("test-key","new.gpx","application/gpx+xml",1))));
        assertThat(response.gpxFileNames()).containsExactly("new.gpx");
        var order=inOrder(storage,drive);order.verify(storage).completePresignedRouteGpxUpload(eq(f.owner()),eq(f.plan()),eq(id),any());
        order.verify(drive).removeRouteGpxLinks(any());order.verify(storage).deleteQuietly("test-only/original.gpx");order.verify(drive).replaceRouteGpxLinks(any(),anyList());
    }
    @Test void directPersistenceMethodsStillRequireTheUseCaseTransaction() {
        assertThatThrownBy(()->store.ownedPlan(7L,10L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->store.delete(20L)).isInstanceOf(IllegalTransactionStateException.class);
    }
    @Test void gpxHttpPresignCompleteAndAbortKeepJsonTransactionModesAndCacheTiming() throws Exception {
        var f=fixture();long id=create(f);metadata(id);clearInvocations(redis,drive,storage);
        when(storage.supportsPresignedUpload()).thenReturn(true);
        when(storage.preparePresignedRouteGpxUploads(eq(f.owner()),eq(f.plan()),eq(id),anyList())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return List.of(new TravelMediaStorageService.PresignedRouteGpxUpload("PUT","https://storage.invalid/synthetic","test/new","stored","new.gpx","xml",1));
        });
        mvc.perform(post("/api/travel/routes/{id}/gpx-files/presign",id).with(user(principal(f))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"files\":[{\"originalFileName\":\"new.gpx\",\"contentType\":\"xml\",\"fileSize\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.uploadMode").value("PRESIGNED"))
                .andExpect(jsonPath("$.uploads[0].uploadUrl").value("https://storage.invalid/synthetic"));
        verifyNoInteractions(redis);
        when(storage.completePresignedRouteGpxUpload(eq(f.owner()),eq(f.plan()),eq(id),any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            return stored("new.gpx","test-only/new.gpx");
        });
        when(storage.loadAsResource("test-only/new.gpx")).thenReturn(gpx());
        mvc.perform(post("/api/travel/routes/{id}/gpx-files/complete",id).with(user(principal(f))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"files\":[{\"objectKey\":\"test/new\",\"originalFileName\":\"new.gpx\",\"contentType\":\"xml\",\"fileSize\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.gpxFileNames[0]").value("new.gpx"))
                .andExpect(jsonPath("$.points[0].latitude").value(37.1));
        verifyEviction(f,2);clearInvocations(redis);
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();return null;})
                .when(storage).abortPresignedRouteGpxUploads(eq(f.owner()),eq(f.plan()),eq(id),anyList());
        mvc.perform(post("/api/travel/routes/{id}/gpx-files/abort",id).with(user(principal(f))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"objectKeys\":[\"test/aborted\"]}")).andExpect(status().isOk());
        verifyNoInteractions(redis);verify(storage).abortPresignedRouteGpxUploads(f.owner(),f.plan(),id,List.of("test/aborted"));
    }

    @Test void multipartHttpUploadUsesTheSameFieldNameAndWritableTransaction() throws Exception {
        var f=fixture();long id=create(f);metadata(id);clearInvocations(redis);
        when(storage.storeRouteGpx(eq(f.owner()),eq(f.plan()),eq(id),any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            return stored("new.gpx","test-only/new.gpx");
        });
        when(storage.loadAsResource("test-only/new.gpx")).thenReturn(gpx());
        mvc.perform(multipart("/api/travel/routes/{id}/gpx-files",id)
                .file(new MockMultipartFile("files","new.gpx","xml",new byte[]{1})).with(user(principal(f))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.gpxFileNames[0]").value("new.gpx"));
        verifyEviction(f,2);
    }

    @Test void allGpxHttpActionsCheckOwnerBeforeStorageAndDoNotEvictOnRejection() throws Exception {
        var f=fixture();var other=fixture();long id=create(f);clearInvocations(redis,drive,storage);
        mvc.perform(multipart("/api/travel/routes/{id}/gpx-files",id)
                .file(new MockMultipartFile("files","new.gpx","xml",new byte[]{1})).with(user(principal(other))).with(csrf()))
                .andExpect(status().isNotFound());
        for(var action:List.of("presign","complete","abort")) {
            String body=action.equals("abort")?"{\"objectKeys\":[\"test/key\"]}":
                    action.equals("complete")?"{\"files\":[{\"objectKey\":\"test/key\",\"originalFileName\":\"new.gpx\",\"contentType\":\"xml\",\"fileSize\":1}]}":
                    "{\"files\":[{\"originalFileName\":\"new.gpx\",\"contentType\":\"xml\",\"fileSize\":1}]}";
            mvc.perform(post("/api/travel/routes/{id}/gpx-files/"+action,id).with(user(principal(other))).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        }
        verifyNoInteractions(storage,drive,redis);
    }

    @Test void gpxHttpAuthenticationCsrfAndRequestValidationAreStillEnforced() throws Exception {
        var f=fixture();long id=create(f);clearInvocations(redis,storage,drive);
        mvc.perform(post("/api/travel/routes/{id}/gpx-files/presign",id).with(user(principal(f))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"files\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/travel/routes/{id}/gpx-files/abort",id).with(user(principal(f)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"objectKeys\":[\"test/key\"]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/travel/routes/{id}/gpx-files/presign",id).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"files\":[]}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(storage,drive,redis);
    }

    @Test void partialDirectUploadFailureRollsBackMetadataWithoutInventingFileCompensation() {
        var f=fixture();long id=create(f);metadata(id);String original=routes.findById(id).orElseThrow().getGpxFilesJson();clearInvocations(redis,storage,drive);
        when(storage.storeRouteGpx(eq(f.owner()),eq(f.plan()),eq(id),any()))
                .thenReturn(stored("first.gpx","test-only/first.gpx")).thenThrow(new IllegalStateException("synthetic second upload"));
        var file=new MockMultipartFile("files","new.gpx","xml",new byte[]{1});
        assertThatThrownBy(()->legacy.uploadRouteGpxFiles(f.owner(),id,List.of(file,file))).hasMessage("synthetic second upload");
        assertThat(routes.findById(id).orElseThrow().getGpxFilesJson()).isEqualTo(original);
        verify(storage).deleteQuietly("test-only/original.gpx");verify(storage,never()).deleteQuietly("test-only/first.gpx");
        verify(storage,never()).abortPresignedRouteGpxUploads(any(),any(),any(),any());verifyNoInteractions(redis);
    }

    @Test void failedPresignedCompletionRetainsOldFilesAndMetadata() {
        var f=fixture();long id=create(f);metadata(id);String original=routes.findById(id).orElseThrow().getGpxFilesJson();clearInvocations(redis,storage,drive);
        when(storage.supportsPresignedUpload()).thenReturn(true);
        when(storage.completePresignedRouteGpxUpload(eq(f.owner()),eq(f.plan()),eq(id),any()))
                .thenReturn(stored("first.gpx","test-only/first.gpx")).thenThrow(new IllegalStateException("synthetic second stat"));
        var item=new TravelRouteGpxUploadCompleteFileRequest("test/new","new.gpx","xml",1);
        assertThatThrownBy(()->legacy.completeRouteGpxUpload(f.owner(),id,new TravelRouteGpxUploadCompleteRequest(List.of(item,item))))
                .hasMessage("synthetic second stat");
        assertThat(routes.findById(id).orElseThrow().getGpxFilesJson()).isEqualTo(original);
        verify(storage,never()).deleteQuietly(any());verifyNoInteractions(drive,redis);
    }

    @Test void successfulReplacementInsideRollbackKeepsDatabaseAndCommitCacheRulesButNotPhysicalFileRestoration() {
        var f=fixture();long id=create(f);metadata(id);String original=routes.findById(id).orElseThrow().getGpxFilesJson();clearInvocations(redis);
        when(storage.supportsPresignedUpload()).thenReturn(true);
        when(storage.completePresignedRouteGpxUpload(eq(f.owner()),eq(f.plan()),eq(id),any())).thenReturn(stored("new.gpx","test-only/new.gpx"));
        when(storage.loadAsResource("test-only/new.gpx")).thenReturn(gpx());
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            routeFiles.complete(f.owner(),id,List.of(new com.playdata.calen.travel.domain.model.TravelRouteUploadData.Completed("test/new","new.gpx","xml",1)));
            assertThat(routes.findById(id).orElseThrow().getGpxFilesJson()).contains("test-only/new.gpx");verifyEviction(f,1);tx.setRollbackOnly();
        });
        assertThat(routes.findById(id).orElseThrow().getGpxFilesJson()).isEqualTo(original);verifyEviction(f,1);
        verify(storage).deleteQuietly("test-only/original.gpx");
    }

    Fixture fixture() {
        return new TransactionTemplate(transactions).execute(tx->{
            var user=new AppUser();user.setLoginId("route-"+UUID.randomUUID());user.setDisplayName("Synthetic route owner");user.setPasswordHash("unused-test-hash");users.save(user);
            var plan=new TravelPlan();plan.setOwner(user);plan.setName("Synthetic trip");plan.setColorHex("#abcdef");plan.setStartDate(LocalDate.of(2026,10,16));plan.setEndDate(LocalDate.of(2026,10,17));plans.save(plan);
            return new Fixture(user.getId(),plan.getId());
        });
    }
    long create(Fixture f){return legacy.createRouteSegment(f.owner(),f.plan(),request(TravelRouteSourceType.MANUAL,null)).id();}
    void metadata(long id){new TransactionTemplate(transactions).executeWithoutResult(tx->routes.findById(id).orElseThrow().setGpxFilesJson("[{\"originalFileName\":\"original.gpx\",\"storagePath\":\"test-only/original.gpx\",\"contentType\":\"application/gpx+xml\",\"fileSize\":1}]"));}
    AppUserPrincipal principal(Fixture f){return AppUserPrincipal.from(users.findById(f.owner()).orElseThrow());}
    TravelRouteSegmentRequest request(TravelRouteSourceType source,String color) {
        return new TravelRouteSegmentRequest(LocalDate.of(2026,10,16)," route ",TravelRouteTransportMode.WALK,new BigDecimal("1.2345"),5,null,source," start "," ",color,null," memo ",
                List.of(new TravelRoutePointRequest(new BigDecimal("37.1234567"),new BigDecimal("127.1234567")),new TravelRoutePointRequest(new BigDecimal("37.2234567"),new BigDecimal("127.2234567"))));
    }
    TravelMediaStorageService.StoredTravelMedia stored(String name,String path){return new TravelMediaStorageService.StoredTravelMedia(name,name,path,"application/gpx+xml",1);}
    ByteArrayResource gpx(){return new ByteArrayResource("<gpx><trkpt lat='37.1' lon='127.1'/><trkpt lat='37.2' lon='127.2'/></gpx>".getBytes(StandardCharsets.UTF_8));}
    void verifyEviction(Fixture f,int count){verify(redis,times(count)).delete(TravelSummaryCache.plansKey(f.owner()),TravelSummaryCache.portfolioKey(f.owner()),TravelSummaryCache.mapKey(f.owner()),TravelSummaryCache.PUBLIC_TRIPS_KEY);}
}
