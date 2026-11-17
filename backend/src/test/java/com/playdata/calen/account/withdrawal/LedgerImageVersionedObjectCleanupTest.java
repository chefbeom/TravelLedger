package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.withdrawal.LedgerImageWithdrawalCleanup.ImageTask;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.storage.LedgerImageStorageMarker;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LedgerImageVersionedObjectCleanupTest {
    private static final String ID = "fixture_storage_id_1234";
    private static final String KEY = "ledger-image-analysis/7/11/01234567-89ab-cdef-0123-456789abcdef.jpg";
    private final MinioClient client = mock(MinioClient.class);
    private final LedgerImageVersionedObjectCleanup cleanup = new LedgerImageVersionedObjectCleanup();
    private final ImageTask target = new ImageTask(7L, 11L, KEY, "cloud-bucket", ID, "version-1");

    @Test void removesOnlyTheVerifiedVersionAndNeverAPrefixNeighbour() throws Exception {
        marker(ID);
        var original = item(KEY, "version-1", false);
        var neighbour = item(KEY + ".other", "neighbour", false);
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(
                List.of(new Result<>(neighbour), new Result<>(original)), List.of(new Result<>(neighbour)));
        assertThat(cleanup.removeRecordedVersion(client, ID, target, () -> true)).isEqualTo(LedgerImageVersionedObjectCleanup.Outcome.DELETED);
        ArgumentCaptor<RemoveObjectArgs> removed = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client).removeObject(removed.capture());
        assertThat(removed.getValue().bucket()).isEqualTo("cloud-bucket");
        assertThat(removed.getValue().object()).isEqualTo(KEY);
        assertThat(removed.getValue().versionId()).isEqualTo("version-1");
    }

    @Test void missingMarkerWrongIdentityOrUnversionedInputNeverDeletes() throws Exception {
        assertThat(cleanup.removeRecordedVersion(client, "different_storage_id", target, () -> true))
                .isEqualTo(LedgerImageVersionedObjectCleanup.Outcome.MANUAL_REVIEW);
        assertThat(cleanup.removeRecordedVersion(client, ID, new ImageTask(7L, 11L, KEY, "cloud-bucket", ID, "null"), () -> true))
                .isEqualTo(LedgerImageVersionedObjectCleanup.Outcome.MANUAL_REVIEW);
        // An ETag is only a comparison hint, never authorization to delete an unversioned object.
        assertThat(cleanup.removeRecordedVersion(client, ID, new ImageTask(7L, 11L, KEY, "cloud-bucket", ID, null, "same-etag"), () -> true))
                .isEqualTo(LedgerImageVersionedObjectCleanup.Outcome.MANUAL_REVIEW);
        marker("wrong_storage_marker_1234");
        assertThat(cleanup.removeRecordedVersion(client, ID, target, () -> true))
                .isEqualTo(LedgerImageVersionedObjectCleanup.Outcome.MARKER_UNCONFIRMED);
        verify(client, never()).listObjects(any());
        verify(client, never()).removeObject(any());
    }

    @Test void invalidOwnerPathBucketOrOversizedVersionNeverReachesStorage() throws Exception {
        for (ImageTask invalid : List.of(
                new ImageTask(8L, 11L, KEY, "cloud-bucket", ID, "version-1"),
                new ImageTask(7L, 11L, "ledger-image-analysis/7/11/../other.jpg", "cloud-bucket", ID, "version-1"),
                new ImageTask(7L, 11L, KEY, "../other-bucket", ID, "version-1"),
                new ImageTask(7L, 11L, KEY, "cloud-bucket", ID, "가".repeat(400)))) {
            assertThat(cleanup.removeRecordedVersion(client, ID, invalid, () -> true))
                    .isEqualTo(LedgerImageVersionedObjectCleanup.Outcome.MANUAL_REVIEW);
        }
        verify(client, never()).getObject(any(GetObjectArgs.class));
        verify(client, never()).listObjects(any());
        verify(client, never()).removeObject(any());
    }

    @Test void missingWrongOrMultipleVersionsRequireReviewBeforeAnyDelete() throws Exception {
        marker(ID);
        var other = item(KEY, "other", false);
        var expected = item(KEY, "version-1", false);
        var older = item(KEY, "older", false);
        var deleted = item(KEY, "version-1", true);
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(
                List.of(), List.of(new Result<>(other)),
                List.of(new Result<>(expected), new Result<>(older)), List.of(new Result<>(deleted)));
        for (int i = 0; i < 4; i++) {
            assertThat(cleanup.removeRecordedVersion(client, ID, target, () -> true)).isEqualTo(LedgerImageVersionedObjectCleanup.Outcome.MANUAL_REVIEW);
        }
        verify(client, never()).removeObject(any());
    }

    @Test void newVersionAfterDeletionIsNotRemovedOrMisreportedAsSuccess() throws Exception {
        marker(ID);
        var original = item(KEY, "version-1", false);
        var newer = item(KEY, "new-version", false);
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(
                List.of(new Result<>(original)), List.of(new Result<>(newer)));
        assertThat(cleanup.removeRecordedVersion(client, ID, target, () -> true)).isEqualTo(LedgerImageVersionedObjectCleanup.Outcome.MANUAL_REVIEW);
        verify(client).removeObject(any());
    }

    @Test void providerFailureIsNotSuccessfulAndDoesNotExposeStorageDetails() throws Exception {
        marker(ID);
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(new Result<Item>(new IOException("private key material"))));
        assertThatThrownBy(() -> cleanup.removeRecordedVersion(client, ID, target, () -> true))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("private key material");
        verify(client, never()).removeObject(any());
    }

    @Test void lastMomentFenceDenialStopsBeforeDeletion() throws Exception {
        marker(ID);
        var original = item(KEY, "version-1", false);
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(new Result<>(original)));
        assertThat(cleanup.removeRecordedVersion(client, ID, target, () -> false))
                .isEqualTo(LedgerImageVersionedObjectCleanup.Outcome.FENCE_REJECTED);
        verify(client, never()).removeObject(any());
    }

    private void marker(String contents) throws Exception {
        when(client.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> new GetObjectResponse(
                new okhttp3.Headers.Builder().build(), "cloud-bucket", "fixture", LedgerImageStorageMarker.OBJECT_KEY,
                new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8))));
    }

    private Item item(String key, String version, boolean marker) {
        Item value = mock(Item.class);
        when(value.objectName()).thenReturn(key); when(value.versionId()).thenReturn(version); when(value.isDeleteMarker()).thenReturn(marker);
        return value;
    }
}
