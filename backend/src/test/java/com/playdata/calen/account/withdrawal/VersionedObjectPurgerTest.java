package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VersionedObjectPurgerTest {
    private static final String KEY = "family-media/7/12/photo.jpg";
    private final MinioClient client = mock(MinioClient.class);
    private final VersionedObjectPurger purger = new VersionedObjectPurger();

    @Test
    void removesEveryExactVersionBeforeItsDeleteMarkerAndNeverTouchesPrefixNeighbours() throws Exception {
        Item marker = item(KEY, "marker", true);
        Item old = item(KEY, "old-version", false);
        Item unversioned = item(KEY, null, false);
        Item neighbour = item(KEY + ".different", "other", false);
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(
                List.of(new Result<>(marker), new Result<>(neighbour), new Result<>(old), new Result<>(unversioned)),
                List.of(new Result<>(neighbour)));

        purger.purgeExactObject(client, "cloud-bucket", KEY);

        ArgumentCaptor<ListObjectsArgs> lists = ArgumentCaptor.forClass(ListObjectsArgs.class);
        verify(client, org.mockito.Mockito.times(2)).listObjects(lists.capture());
        assertThat(lists.getAllValues()).allSatisfy(args -> {
            assertThat(args.bucket()).isEqualTo("cloud-bucket");
            assertThat(args.prefix()).isEqualTo(KEY);
            assertThat(args.includeVersions()).isTrue();
            assertThat(args.recursive()).isTrue();
        });
        ArgumentCaptor<RemoveObjectArgs> removed = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client, org.mockito.Mockito.times(3)).removeObject(removed.capture());
        assertThat(removed.getAllValues()).allSatisfy(args -> {
            assertThat(args.bucket()).isEqualTo("cloud-bucket");
            assertThat(args.object()).isEqualTo(KEY);
        });
        assertThat(removed.getAllValues()).extracting(RemoveObjectArgs::versionId)
                .containsExactly("old-version", "null", "marker");
    }

    @Test
    void emptyTargetNeverCallsStorage() throws Exception {
        assertThatThrownBy(() -> purger.purgeExactObject(client, "cloud-bucket", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purger.purgeExactObject(client, "cloud-bucket", "folder/"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).listObjects(any());
        verify(client, never()).removeObject(any());
    }

    @Test
    void listingFailureDoesNotDeleteAnythingOrExposeItsProviderMessage() throws Exception {
        when(client.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(List.of(new Result<Item>(new IOException("sensitive storage details"))));
        assertThatThrownBy(() -> purger.purgeExactObject(client, "cloud-bucket", KEY))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageNotContaining("sensitive storage details");
        verify(client, never()).removeObject(any());
    }

    @Test
    void removalFailureAndAnObjectThatReappearsNeverCountAsSuccess() throws Exception {
        Item old = item(KEY, "version", false);
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(new Result<>(old)));
        doThrow(new IOException("private storage failure")).when(client).removeObject(any(RemoveObjectArgs.class));
        assertThatThrownBy(() -> purger.purgeExactObject(client, "cloud-bucket", KEY))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageNotContaining("private storage failure");

        MinioClient racingClient = mock(MinioClient.class);
        when(racingClient.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(new Result<>(old)));
        assertThatThrownBy(() -> purger.purgeExactObject(racingClient, "cloud-bucket", KEY))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void refusesAnUnboundedPrefixListingBeforeDeletingAnyVersion() throws Exception {
        Item neighbour = item(KEY + ".other", "v", false);
        when(client.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(Collections.nCopies(20_001, new Result<>(neighbour)));
        assertThatThrownBy(() -> purger.purgeExactObject(client, "cloud-bucket", KEY))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(client, never()).removeObject(any());
    }

    private Item item(String key, String version, boolean marker) {
        Item value = mock(Item.class);
        when(value.objectName()).thenReturn(key);
        when(value.versionId()).thenReturn(version);
        when(value.isDeleteMarker()).thenReturn(marker);
        return value;
    }
}
