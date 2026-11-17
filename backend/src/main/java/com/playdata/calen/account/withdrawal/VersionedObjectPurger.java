package com.playdata.calen.account.withdrawal;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Permanently removes versions of one exact object, never an object prefix.
 * Callers must first prove ownership, remove other references, and prevent new writes.
 * This helper is not connected to the account-withdrawal HTTP flow yet.
 */
@Component
public class VersionedObjectPurger {
    private static final int MAX_EXACT_VERSIONS = 10_000;
    private static final int MAX_SCANNED_ENTRIES = 20_000;

    public void purgeExactObject(MinioClient client, String bucket, String objectKey) {
        if (client == null || !StringUtils.hasText(bucket) || !StringUtils.hasText(objectKey)
                || objectKey.startsWith("/") || objectKey.endsWith("/") || objectKey.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid exact storage target.");
        }

        try {
            // Enumerate before deletion: deleting while paging can skip later versions.
            List<Version> versions = listExactVersions(client, bucket, objectKey);
            // Removing a delete marker can expose a previous version, so remove markers last.
            versions.sort(Comparator.comparing(Version::deleteMarker));
            for (Version version : versions) {
                client.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket).object(objectKey).versionId(version.id()).build());
            }
            if (!listExactVersions(client, bucket, objectKey).isEmpty()) {
                throw new ServiceUnavailableException("파일의 모든 저장 버전 삭제를 확인할 수 없습니다.");
            }
        } catch (ServiceUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            // Do not include object keys, provider responses, or credentials in logs or client messages.
            throw new ServiceUnavailableException("파일 저장소의 삭제 결과를 확인할 수 없습니다.");
        }
    }

    private List<Version> listExactVersions(MinioClient client, String bucket, String objectKey) throws Exception {
        Iterable<Result<Item>> entries = client.listObjects(ListObjectsArgs.builder()
                .bucket(bucket).prefix(objectKey).recursive(true).includeVersions(true).build());
        if (entries == null) throw new ServiceUnavailableException("파일 저장 버전을 조회할 수 없습니다.");
        List<Version> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int scanned = 0;
        for (Result<Item> entry : entries) {
            if (++scanned > MAX_SCANNED_ENTRIES || entry == null) {
                throw new ServiceUnavailableException("파일 저장 버전 목록을 안전하게 확인할 수 없습니다.");
            }
            Item item = entry.get();
            if (item == null || item.objectName() == null) {
                throw new ServiceUnavailableException("파일 저장 버전 목록을 확인할 수 없습니다.");
            }
            if (!objectKey.equals(item.objectName())) continue;
            String id = item.versionId();
            // S3 uses the literal version ID "null" for objects created without versioning.
            if (id == null) id = "null";
            if (!StringUtils.hasText(id)) {
                throw new ServiceUnavailableException("파일 저장 버전을 확인할 수 없습니다.");
            }
            if (seen.add(id)) result.add(new Version(id, item.isDeleteMarker()));
            if (result.size() > MAX_EXACT_VERSIONS) {
                throw new ServiceUnavailableException("파일 저장 버전이 너무 많아 자동 삭제할 수 없습니다.");
            }
        }
        return result;
    }

    private record Version(String id, boolean deleteMarker) {}
}
