package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SupportInquiry;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SupportInquiryRepository;
import com.playdata.calen.common.exception.NotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:support-withdrawal-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class SupportInquiryWithdrawalConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired SupportInquiryRepository inquiries;
    @Autowired SupportInquiryService support;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SupportInquiryStorageService storage;

    @Test void attachmentIsRemovedOnlyAfterAConfirmedTransactionRollback() {
        Long userId = user();
        String path = "user-" + userId + "/" + UUID.randomUUID() + ".png";
        var file = new MockMultipartFile("attachment", "receipt.png", "image/png", new byte[]{1});
        when(storage.store(eq(userId), any())).thenReturn(
                new SupportInquiryStorageService.StoredSupportAttachment(path, "receipt.png", "image/png", 1L));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            support.createInquiry(userId, "제목", "내용", file);
            status.setRollbackOnly();
        });
        verify(storage).deleteQuietly(path);
        assertThat(inquiries.findAllBySenderIdOrderByCreatedAtDescIdDesc(userId,
                org.springframework.data.domain.PageRequest.of(0, 5))).isEmpty();
    }

    @Test void committedInquiryKeepsItsAttachment() {
        Long userId = user();
        String path = "user-" + userId + "/" + UUID.randomUUID() + ".png";
        var file = new MockMultipartFile("attachment", "receipt.png", "image/png", new byte[]{1});
        when(storage.store(eq(userId), any())).thenReturn(
                new SupportInquiryStorageService.StoredSupportAttachment(path, "receipt.png", "image/png", 1L));
        var saved = support.createInquiry(userId, "제목", "내용", file);
        assertThat(inquiries.findById(saved.id())).isPresent();
        verify(storage, never()).deleteQuietly(path);
    }

    @Test void createAndReplyWaitingForDeactivationCannotWriteAfterward() throws Exception {
        Long userId = user();
        Long senderId = user();
        var inquiry = new SupportInquiry();
        inquiry.setSender(users.findById(senderId).orElseThrow());
        inquiry.setTitle("제목"); inquiry.setContent("내용");
        Long inquiryId = inquiries.saveAndFlush(inquiry).getId();
        var pool = Executors.newFixedThreadPool(3);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.lockForAccountSecurityUpdate(userId).orElseThrow().setActive(false);
                users.flush();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<Callable<Object>> operations = List.of(
                    () -> support.createInquiry(userId, "제목", "내용", null),
                    () -> support.reply(userId, inquiryId, "답변"));
            var writes = operations.stream().map(pool::submit).toList();
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            }
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(NotFoundException.class);
            }
            assertThat(inquiries.findById(inquiryId).orElseThrow().getReplyContent()).isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private Long user() {
        var user = new AppUser();
        user.setLoginId("support-" + UUID.randomUUID());
        user.setDisplayName("문의 사용자");
        return users.saveAndFlush(user).getId();
    }
}
