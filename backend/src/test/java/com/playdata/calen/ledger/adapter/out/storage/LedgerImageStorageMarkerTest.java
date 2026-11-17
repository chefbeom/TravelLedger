package com.playdata.calen.ledger.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.common.storage.LedgerImageStorageMarker;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LedgerImageStorageMarkerTest {
    private final MinioClient client = mock(MinioClient.class);
    private static final String ID = "fixture_storage_id_1234";

    @Test void optionalOrInvalidConfigurationNeverContactsStorage() throws Exception {
        assertThat(LedgerImageStorageMarker.matches(client, "bucket", "")).isFalse();
        assertThat(LedgerImageStorageMarker.matches(client, "bucket", "id/unsafe" )).isFalse();
        assertThat(LedgerImageStorageMarker.matches(client, " ", ID)).isFalse();
        verify(client, never()).getObject(any(GetObjectArgs.class));
    }

    @Test void requiresTheExpectedMarkerContentsAndBoundsTheRead() throws Exception {
        when(client.getObject(any(GetObjectArgs.class)))
                .thenReturn(response(ID + "\n"), response("another_storage_id_1234"), response("x".repeat(129)));
        assertThat(LedgerImageStorageMarker.matches(client, "bucket", ID)).isTrue();
        assertThat(LedgerImageStorageMarker.matches(client, "bucket", ID)).isFalse();
        assertThat(LedgerImageStorageMarker.matches(client, "bucket", ID)).isFalse();
    }

    private GetObjectResponse response(String value) {
        return new GetObjectResponse(new okhttp3.Headers.Builder().build(), "bucket", "fixture",
                LedgerImageStorageMarker.OBJECT_KEY, new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }
}
