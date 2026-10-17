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
import com.playdata.calen.sharing.service.RecordSharingService;
import com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache;
import com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase;
import com.playdata.calen.travel.application.port.out.TravelSharingStore;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.dto.*;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:travel-sharing-refactoring;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelSharingRefactoringIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelPlanShareRepository shares;
    @Autowired TravelShareGroupRepository groups;
    @Autowired TravelShareGroupMemberRepository members;
    @Autowired ManageTravelSharingUseCase sharing;
    @Autowired TravelSharingStore store;
    @Autowired TravelService legacy;
    @Autowired RecordSharingService recordSharing;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelDriveLinkService drive;
    @MockitoBean TravelClusterRefreshCoordinator clusters;
    record Fixture(long owner, long plan, String login) {}

    @Test void groupHttpCreateListReplaceAndDeleteKeepOwnerScopeMemberOrderAndJson() throws Exception {
        var owner = fixture(TravelPlanStatus.COMPLETED); var first = fixture(TravelPlanStatus.COMPLETED); var second = fixture(TravelPlanStatus.COMPLETED);
        var created = mvc.perform(post("/api/travel/share-groups").with(user(principal(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new TravelShareGroupRequest(" Family ", List.of(first.login(), second.login(), first.login())))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Family"))
                .andExpect(jsonPath("$.recipients.length()").value(2)).andExpect(jsonPath("$.recipients[0].userId").value(first.owner()))
                .andExpect(jsonPath("$.recipients[1].loginId").value(second.login())).andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsByteArray()).get("id").asLong();
        var oldCreatedAt = groups.findById(id).orElseThrow().getCreatedAt();
        mvc.perform(get("/api/travel/share-groups").with(user(principal(owner)))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/travel/share-groups").with(user(principal(first)))).andExpect(status().isOk()).andExpect(content().json("[]"));
        assertThat(recordSharing.getGroups(first.owner())).anySatisfy(group -> {
            assertThat(group.id()).isEqualTo(id); assertThat(group.ownerId()).isEqualTo(owner.owner());
            assertThat(group.members()).extracting(member -> member.userId()).containsExactly(owner.owner(), first.owner(), second.owner());
        });
        var replaced = sharing.saveGroup(owner.owner(), "family", List.of(second.login()));
        assertThat(replaced.id()).isEqualTo(id); assertThat(replaced.createdAt()).isEqualTo(oldCreatedAt);
        assertThat(replaced.recipients()).extracting(recipient -> recipient.id()).containsExactly(second.owner());
        assertThat(members.findAllByGroupIdOrderByIdAsc(id)).hasSize(1);
        mvc.perform(delete("/api/travel/share-groups/{id}", id).with(user(principal(first))).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/travel/share-groups/{id}", id).with(user(principal(owner))).with(csrf())).andExpect(status().isOk());
        assertThat(groups.findById(id)).isEmpty(); assertThat(members.findAllByGroupIdOrderByIdAsc(id)).isEmpty();
        verifyNoInteractions(redis, storage, drive, clusters);
    }

    @Test void invalidRecipientDoesNotClearExistingMembersAndInactiveOrSelfCannotBeAdded() {
        var owner = fixture(TravelPlanStatus.COMPLETED); var first = fixture(TravelPlanStatus.COMPLETED); var second = fixture(TravelPlanStatus.COMPLETED);
        var original = sharing.saveGroup(owner.owner(), "Family", List.of(first.login()));
        var before = members.findAllByGroupIdOrderByIdAsc(original.id()).get(0).getId();
        assertThatThrownBy(() -> sharing.saveGroup(owner.owner(), "Family", List.of(second.login(), "missing-synthetic-login")))
                .hasMessage("공유 그룹 사용자를 찾을 수 없습니다: missing-synthetic-login");
        inactive(second);
        assertThatThrownBy(() -> sharing.saveGroup(owner.owner(), "Family", List.of(second.login())))
                .hasMessage("공유 그룹 사용자를 찾을 수 없습니다: " + second.login());
        assertThatThrownBy(() -> sharing.saveGroup(owner.owner(), "Family", List.of(owner.login())))
                .hasMessage("자기 자신은 공유 그룹에 넣을 수 없습니다.");
        assertThat(members.findAllByGroupIdOrderByIdAsc(original.id())).extracting(TravelShareGroupMember::getId).containsExactly(before);
        verifyNoInteractions(redis, storage, drive, clusters);
    }

    @Test void outerRollbackRestoresMembershipAfterTheActualDeleteFlushAndReplacement() {
        var owner = fixture(TravelPlanStatus.COMPLETED); var first = fixture(TravelPlanStatus.COMPLETED); var second = fixture(TravelPlanStatus.COMPLETED);
        var original = sharing.saveGroup(owner.owner(), "Family", List.of(first.login()));
        long originalMember = members.findAllByGroupIdOrderByIdAsc(original.id()).get(0).getId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var updated = sharing.saveGroup(owner.owner(), "FAMILY", List.of(second.login()));
            assertThat(updated.id()).isEqualTo(original.id());
            assertThat(updated.recipients()).extracting(recipient -> recipient.id()).containsExactly(second.owner());
            assertThat(members.findAllByGroupIdOrderByIdAsc(original.id())).extracting(TravelShareGroupMember::getId).doesNotContain(originalMember);
            tx.setRollbackOnly();
        });
        assertThat(groups.findById(original.id()).orElseThrow().getName()).isEqualTo("Family");
        assertThat(members.findAllByGroupIdOrderByIdAsc(original.id())).extracting(TravelShareGroupMember::getId).containsExactly(originalMember);
        verifyNoInteractions(redis, storage, drive, clusters);
    }

    @Test void groupReadsKeepUpdatedDescThenIdDescAndDoNotFilterInactiveMembersFromTheOwnerList() {
        var owner = fixture(TravelPlanStatus.COMPLETED); var member = fixture(TravelPlanStatus.COMPLETED);
        var a = sharing.saveGroup(owner.owner(), "A", List.of(member.login()));
        var b = sharing.saveGroup(owner.owner(), "B", List.of(member.login()));
        var fixed = LocalDateTime.of(2026, 10, 16, 12, 0);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            groups.findById(a.id()).orElseThrow().setUpdatedAt(fixed); groups.findById(b.id()).orElseThrow().setUpdatedAt(fixed);
        });
        inactive(member);
        assertThat(sharing.groups(owner.owner())).extracting(group -> group.id()).containsExactly(b.id(), a.id());
        assertThat(sharing.groups(owner.owner()).get(0).recipients()).extracting(recipient -> recipient.id()).containsExactly(member.owner());
        // Record-sharing's separate participant view still hides inactive accounts.
        assertThat(recordSharing.getGroups(owner.owner()).get(0).members()).extracting(value -> value.userId()).containsExactly(owner.owner());
    }

    @Test void searchHttpExcludesOwnerAndInactiveAccountsAndRetainsTenResultLimit() throws Exception {
        var owner = fixture(TravelPlanStatus.COMPLETED);
        String query = "synthetic-search-" + UUID.randomUUID();
        for (int index = 0; index < 12; index++) {
            var account = fixture(TravelPlanStatus.COMPLETED);
            int position = index;
            new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(account.owner()).orElseThrow().setDisplayName(query + String.format("%02d", position)));
        }
        new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(owner.owner()).orElseThrow().setDisplayName(query + "-owner"));
        var inactive = fixture(TravelPlanStatus.COMPLETED);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { var account = users.findById(inactive.owner()).orElseThrow(); account.setDisplayName(query + "-inactive"); account.setActive(false); });
        var response = mvc.perform(get("/api/travel/share-recipients").param("q", " " + query + " ").with(user(principal(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(10)).andReturn();
        for (var item : json.readTree(response.getResponse().getContentAsByteArray())) {
            assertThat(item.get("userId").asLong()).isNotIn(owner.owner(), inactive.owner());
        }
        mvc.perform(get("/api/travel/share-recipients").with(user(principal(owner)))).andExpect(status().isOk()).andExpect(content().json("[]"));
        verifyNoInteractions(redis, storage, drive, clusters);
    }

    @Test void shareHttpReusesExistingRowListsOnlyOwnedSharesAndCancelRevokesTheRecipientView() throws Exception {
        var owner = fixture(TravelPlanStatus.COMPLETED); var recipient = fixture(TravelPlanStatus.COMPLETED); var other = fixture(TravelPlanStatus.COMPLETED);
        var created = mvc.perform(post("/api/travel/plans/{id}/shares", owner.plan()).with(user(principal(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new TravelPlanShareRequest(" " + recipient.login() + " "))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planId").value(owner.plan()))
                .andExpect(jsonPath("$.planName").value("Synthetic shared trip")).andExpect(jsonPath("$.recipientLoginId").value(recipient.login()))
                .andExpect(jsonPath("$.sharedAt").exists()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsByteArray()).get("id").asLong();
        var originalTime = shares.findById(id).orElseThrow().getCreatedAt();
        assertThat(sharing.shareCompletedPlan(owner.owner(), owner.plan(), recipient.login()).id()).isEqualTo(id);
        assertThat(shares.findById(id).orElseThrow().getCreatedAt()).isEqualTo(originalTime);
        mvc.perform(get("/api/travel/plans/{id}/shares", owner.plan()).with(user(principal(owner)))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/travel/plans/{id}/shares", owner.plan()).with(user(principal(recipient)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/travel/shared-exhibits/{id}", id).with(user(principal(recipient)))).andExpect(status().isOk());
        mvc.perform(get("/api/travel/shared-exhibits/{id}", id).with(user(principal(other)))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/travel/plans/{plan}/shares/{share}", owner.plan(), id).with(user(principal(recipient))).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/travel/plans/{plan}/shares/{share}", owner.plan(), id).with(user(principal(owner))).with(csrf())).andExpect(status().isOk());
        assertThat(shares.findById(id)).isEmpty();
        mvc.perform(get("/api/travel/shared-exhibits/{id}", id).with(user(principal(recipient)))).andExpect(status().isNotFound());
        verify(redis, times(6)).delete(TravelSummaryCache.PUBLIC_TRIPS_KEY);
        verifyNoInteractions(storage, drive, clusters);
    }

    @Test void foreignOwnerIncompleteSelfInactiveAndMissingRecipientsCannotCreateShares() throws Exception {
        var owner = fixture(TravelPlanStatus.PLANNED); var recipient = fixture(TravelPlanStatus.COMPLETED);
        long before = shares.count();
        mvc.perform(post("/api/travel/plans/{id}/shares", owner.plan()).with(user(principal(recipient))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new TravelPlanShareRequest(owner.login())))).andExpect(status().isNotFound());
        assertThatThrownBy(() -> sharing.shareCompletedPlan(owner.owner(), owner.plan(), "missing"))
                .hasMessage("완성된 여행만 다른 사용자에게 공유할 수 있습니다.");
        changePlanStatus(owner, TravelPlanStatus.COMPLETED);
        assertThatThrownBy(() -> sharing.shareCompletedPlan(owner.owner(), owner.plan(), owner.login())).hasMessage("자기 자신에게는 여행 전시를 공유할 수 없습니다.");
        inactive(recipient);
        assertThatThrownBy(() -> sharing.shareCompletedPlan(owner.owner(), owner.plan(), recipient.login())).hasMessage("공유할 사용자를 찾을 수 없습니다.");
        assertThat(shares.count()).isEqualTo(before);
        verifyNoInteractions(redis, storage, drive, clusters);
    }

    @Test void publicToggleHttpKeepsCompletionGateNullTimeAndBothImmediateAndCommitCacheInvalidations() throws Exception {
        var owner = fixture(TravelPlanStatus.PLANNED); var other = fixture(TravelPlanStatus.COMPLETED);
        mvc.perform(put("/api/travel/plans/{id}/public-share", owner.plan()).with(user(principal(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"publicShared\":true}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/travel/plans/{id}/public-share", owner.plan()).with(user(principal(other))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"publicShared\":true}")).andExpect(status().isNotFound());
        verifyNoInteractions(redis);
        changePlanStatus(owner, TravelPlanStatus.COMPLETED);
        mvc.perform(put("/api/travel/plans/{id}/public-share", owner.plan()).with(user(principal(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"publicShared\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicShared").value(true)).andExpect(jsonPath("$.publicSharedAt").exists());
        assertThat(plans.findById(owner.plan()).orElseThrow().getPublicSharedAt()).isNotNull();
        verifyOwnerEviction(owner, 2); verify(redis, times(2)).delete(TravelSummaryCache.PUBLIC_TRIPS_KEY);
        clearInvocations(redis); changePlanStatus(owner, TravelPlanStatus.PLANNED);
        mvc.perform(put("/api/travel/plans/{id}/public-share", owner.plan()).with(user(principal(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"publicShared\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicShared").value(false)).andExpect(jsonPath("$.publicSharedAt").doesNotExist());
        assertThat(plans.findById(owner.plan()).orElseThrow().getPublicSharedAt()).isNull();
        verifyOwnerEviction(owner, 2); verify(redis, times(2)).delete(TravelSummaryCache.PUBLIC_TRIPS_KEY);
        verifyNoInteractions(storage, drive, clusters);
    }

    @Test void shareCreateAndCancelRollbackKeepTheRowsAndOnlyImmediateCacheEviction() {
        var owner = fixture(TravelPlanStatus.COMPLETED); var recipient = fixture(TravelPlanStatus.COMPLETED);
        long before = shares.count();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            sharing.shareCompletedPlan(owner.owner(), owner.plan(), recipient.login());
            assertThat(shares.count()).isEqualTo(before + 1); verify(redis).delete(TravelSummaryCache.PUBLIC_TRIPS_KEY); tx.setRollbackOnly();
        });
        assertThat(shares.count()).isEqualTo(before); verify(redis).delete(TravelSummaryCache.PUBLIC_TRIPS_KEY);
        clearInvocations(redis);
        long id = sharing.shareCompletedPlan(owner.owner(), owner.plan(), recipient.login()).id(); clearInvocations(redis);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            sharing.cancelShare(owner.owner(), owner.plan(), id); assertThat(shares.findById(id)).isEmpty(); tx.setRollbackOnly();
        });
        assertThat(shares.findById(id)).isPresent(); verify(redis).delete(TravelSummaryCache.PUBLIC_TRIPS_KEY);
    }

    @Test void publicToggleRollbackAndCacheFailureCannotCommitTheVisibilityChange() {
        var owner = fixture(TravelPlanStatus.COMPLETED);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            sharing.publicShare(owner.owner(), owner.plan(), true);
            assertThat(plans.findById(owner.plan()).orElseThrow().getPublicShared()).isTrue(); verifyOwnerEviction(owner, 1); tx.setRollbackOnly();
        });
        assertThat(plans.findById(owner.plan()).orElseThrow().getPublicShared()).isFalse(); verifyOwnerEviction(owner, 1);
        verify(redis).delete(TravelSummaryCache.PUBLIC_TRIPS_KEY); clearInvocations(redis);
        doThrow(new IllegalStateException("synthetic cache unavailable")).when(redis).delete(TravelSummaryCache.PUBLIC_TRIPS_KEY);
        assertThatThrownBy(() -> sharing.publicShare(owner.owner(), owner.plan(), true)).hasMessage("synthetic cache unavailable");
        assertThat(plans.findById(owner.plan()).orElseThrow().getPublicShared()).isFalse(); verifyOwnerEviction(owner, 1);
    }

    @Test void mandatoryStoreRejectsUnscopedCallsAndInactiveOwnerGuardsStayOnTheExistingOperations() {
        var owner = fixture(TravelPlanStatus.COMPLETED); var recipient = fixture(TravelPlanStatus.COMPLETED);
        assertThatThrownBy(() -> store.ownedPlan(owner.owner(), owner.plan())).isInstanceOf(IllegalTransactionStateException.class);
        inactive(owner);
        assertThatThrownBy(() -> sharing.groups(owner.owner())).hasMessage("사용자 계정을 찾을 수 없습니다.");
        assertThatThrownBy(() -> sharing.searchRecipients(owner.owner(), "recipient")).hasMessage("사용자 계정을 찾을 수 없습니다.");
        assertThatThrownBy(() -> sharing.saveGroup(owner.owner(), "Family", List.of(recipient.login()))).hasMessage("사용자 계정을 찾을 수 없습니다.");
        assertThatThrownBy(() -> sharing.shareCompletedPlan(owner.owner(), owner.plan(), recipient.login())).hasMessage("사용자 계정을 찾을 수 없습니다.");
        // Existing internal public-toggle behavior checks plan ownership, not account activity.
        assertThat(sharing.publicShare(owner.owner(), owner.plan(), null).publicShared()).isFalse();
    }

    @Test void authenticationCsrfAndRequestValidationRemainOnAllMovedRoutes() throws Exception {
        var owner = fixture(TravelPlanStatus.COMPLETED);
        mvc.perform(get("/api/travel/share-groups")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/travel/share-groups").with(user(principal(owner))).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/travel/share-groups").with(user(principal(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/travel/plans/{id}/shares", owner.plan()).with(user(principal(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/travel/plans/{id}/public-share", owner.plan()).with(user(principal(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(redis, storage, drive, clusters);
    }

    @Test void legacyManagementEntryPointsDelegateToTheSameTransactionsAndUnchangedDtos() {
        var owner = fixture(TravelPlanStatus.COMPLETED); var recipient = fixture(TravelPlanStatus.COMPLETED);
        var group = legacy.saveShareGroup(owner.owner(), new TravelShareGroupRequest("Family", List.of(recipient.login())));
        assertThat(legacy.getShareGroups(owner.owner())).extracting(TravelShareGroupResponse::id).containsExactly(group.id());
        var share = legacy.shareCompletedPlan(owner.owner(), owner.plan(), recipient.login());
        assertThat(legacy.getPlanShares(owner.owner(), owner.plan())).extracting(TravelPlanShareResponse::id).containsExactly(share.id());
        assertThat(legacy.updatePlanPublicShare(owner.owner(), owner.plan(), true).publicShared()).isTrue();
        legacy.cancelPlanShare(owner.owner(), owner.plan(), share.id()); legacy.deleteShareGroup(owner.owner(), group.id());
        assertThat(shares.findById(share.id())).isEmpty(); assertThat(groups.findById(group.id())).isEmpty();
    }

    Fixture fixture(TravelPlanStatus status) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var account = new AppUser(); account.setLoginId("sharing-" + UUID.randomUUID()); account.setDisplayName("Synthetic sharing user");
            account.setPasswordHash("unused-test-hash"); users.save(account);
            var plan = new TravelPlan(); plan.setOwner(account); plan.setName("Synthetic shared trip"); plan.setStatus(status); plan.setColorHex("#abcdef");
            plan.setStartDate(LocalDate.of(2026, 10, 16)); plan.setEndDate(LocalDate.of(2026, 10, 17)); plans.save(plan);
            return new Fixture(account.getId(), plan.getId(), account.getLoginId());
        });
    }
    AppUserPrincipal principal(Fixture f) { return AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()); }
    void inactive(Fixture f) { new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(f.owner()).orElseThrow().setActive(false)); }
    void changePlanStatus(Fixture f, TravelPlanStatus status) { new TransactionTemplate(transactions).executeWithoutResult(tx -> plans.findById(f.plan()).orElseThrow().setStatus(status)); }
    void verifyOwnerEviction(Fixture f, int count) { verify(redis, times(count)).delete(TravelSummaryCache.plansKey(f.owner()), TravelSummaryCache.portfolioKey(f.owner()), TravelSummaryCache.mapKey(f.owner()), TravelSummaryCache.PUBLIC_TRIPS_KEY); }
}
