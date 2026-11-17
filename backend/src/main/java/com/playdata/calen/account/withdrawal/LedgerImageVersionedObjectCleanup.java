package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.withdrawal.LedgerImageWithdrawalCleanup.ImageTask;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.storage.LedgerImageStorageMarker;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** An internal, exact-version operation. Callers must first verify account, task and references. */
@Component
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class LedgerImageVersionedObjectCleanup {
    private static final int MAX_SCANNED = 20_000;

    public Outcome removeRecordedVersion(MinioClient client, String configuredStorageId, ImageTask target, BooleanSupplier stillAuthorized) {
        if (target == null || !target.validForVersionCleanup() || client == null
                || stillAuthorized == null || !target.storageId().equals(configuredStorageId)) return Outcome.MANUAL_REVIEW;
        if (!LedgerImageStorageMarker.matches(client, target.bucket(), configuredStorageId)) return Outcome.MARKER_UNCONFIRMED;
        try {
            List<Item> before = exactVersions(client, target);
            if (before.size() != 1) return Outcome.MANUAL_REVIEW;
            Item only = before.get(0);
            if (only.isDeleteMarker() || !target.versionId().equals(only.versionId())) return Outcome.MANUAL_REVIEW;
            // The marker/listing can be slow: revalidate the durable claim and references just before removal.
            if (!stillAuthorized.getAsBoolean()) return Outcome.FENCE_REJECTED;
            client.removeObject(RemoveObjectArgs.builder().bucket(target.bucket()).object(target.objectKey())
                    .versionId(target.versionId()).build());
            // A new object or version may have appeared after the initial check. Never delete it.
            return exactVersions(client, target).isEmpty() ? Outcome.DELETED : Outcome.MANUAL_REVIEW;
        } catch (Exception unconfirmed) {
            // A timeout may occur after deletion; a later missing version needs manual reconciliation.
            throw new ServiceUnavailableException("이미지 원본 저장 버전의 삭제 결과를 확인할 수 없습니다.");
        }
    }

    private List<Item> exactVersions(MinioClient client, ImageTask target) throws Exception {
        Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder().bucket(target.bucket())
                .prefix(target.objectKey()).recursive(true).includeVersions(true).build());
        if (results == null) throw new IllegalStateException("Storage listing unavailable.");
        List<Item> exact = new ArrayList<>();
        int count = 0;
        for (Result<Item> result : results) {
            if (++count > MAX_SCANNED || result == null) throw new IllegalStateException("Storage listing is unsafe.");
            Item item = result.get();
            if (item == null || item.objectName() == null) throw new IllegalStateException("Storage listing is invalid.");
            if (target.objectKey().equals(item.objectName())) {
                exact.add(item);
                // Two matching rows are sufficient to refuse deletion, including duplicate version entries.
                if (exact.size() > 1) return exact;
            }
        }
        return exact;
    }

    public enum Outcome { DELETED, MARKER_UNCONFIRMED, MANUAL_REVIEW, FENCE_REJECTED }
}
