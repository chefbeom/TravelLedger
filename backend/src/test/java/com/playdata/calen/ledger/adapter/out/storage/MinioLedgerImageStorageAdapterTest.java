package com.playdata.calen.ledger.adapter.out.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.*;
import com.playdata.calen.ledger.adapter.in.image.MultipartLedgerImageContent;
import io.minio.*;
import java.io.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.multipart.MultipartFile;

class MinioLedgerImageStorageAdapterTest {
    private final MinioClient client=mock(MinioClient.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<MinioClient> clients=mock(ObjectProvider.class);
    private final MinioProperties properties=new MinioProperties();
    private final MinioLedgerImageStorageAdapter adapter=new MinioLedgerImageStorageAdapter(clients,properties);

    @BeforeEach void configured() {
        when(clients.getIfAvailable()).thenReturn(client);
        properties.setEndpoint("http://storage.fixture.internal:9000"); properties.setAccessKey("fixture-access"); properties.setSecretKey("fixture-secret");
        properties.setBucket_cloud("cloud-bucket"); properties.setBucket_work("work-bucket");
    }
    static class TrackedStream extends ByteArrayInputStream {
        int closes;
        TrackedStream() { super(new byte[]{1,2,3,4}); }
        @Override public void close() throws IOException { closes++; super.close(); }
    }
    private MultipartFile file(String name,String type,InputStream stream) throws Exception {
        var file=mock(MultipartFile.class); when(file.getOriginalFilename()).thenReturn(name); when(file.getContentType()).thenReturn(type);
        when(file.getSize()).thenReturn(4L); when(file.getInputStream()).thenReturn(stream); return file;
    }
    @Test void uploadUsesTheOriginalStreamAndClosesItWithoutMultipartByteCopy() throws Exception {
        var stream=new TrackedStream(); var file=file("../private/receipt.JPG"," image/jpeg ",stream);
        LocalDateTime before=LocalDateTime.now();
        when(client.putObject(any())).thenAnswer(call -> {
            PutObjectArgs args=call.getArgument(0); assertThat(args.bucket()).isEqualTo("cloud-bucket");
            assertThat(args.object()).matches("ledger-image-analysis/7/11/[a-f0-9-]{36}\\.jpg");
            assertThat(args.objectSize()).isEqualTo(4); assertThat(args.contentType()).isEqualTo("image/jpeg");
            assertThat(stream.closes).isZero(); assertThat(args.stream().readAllBytes()).containsExactly(1,2,3,4); return null;
        });
        var result=adapter.store(7L,11L,new MultipartLedgerImageContent(file));
        assertThat(result.objectKey()).startsWith("ledger-image-analysis/7/11/").endsWith(".jpg").doesNotContain("private","receipt");
        assertThat(result.storedAt()).isBetween(before,LocalDateTime.now()); assertThat(stream.closes).isEqualTo(1);
        verify(file,never()).getBytes(); verify(file).getInputStream();
    }
    @ParameterizedTest @CsvSource(value={"photo.JPEG,.jpeg","no-extension,.bin","file.,.bin","NULL,.bin","picture.abc_def,.abcdef","file.abcdefghijklm,.bin","archive.tar.gz,.gz"},nullValues="NULL")
    void extensionsKeepExistingNormalizationAndOwnerJobNamespacing(String name,String extension) throws Exception {
        var file=file(name,null,new TrackedStream());
        var result=adapter.store(7L,11L,new MultipartLedgerImageContent(file));
        assertThat(result.objectKey()).startsWith("ledger-image-analysis/7/11/").endsWith(extension);
        verify(client).putObject(argThat(args -> args.bucket().equals("cloud-bucket")));
    }
    @Test void cloudBucketIsPreferredAndWorkBucketRemainsTheFallback() throws Exception {
        properties.setBucket_cloud("  "); var stream=new TrackedStream();
        adapter.store(7L,11L,new MultipartLedgerImageContent(file("receipt.png",null,stream)));
        verify(client).putObject(argThat(args -> args.bucket().equals("work-bucket"))); assertThat(adapter.resolveBucket()).isEqualTo("work-bucket");
    }
    @Test void missingConfigurationFailsBeforeInputValidationAndDoesNotContactMinio() {
        properties.setEndpoint(null); assertThat(adapter.supportsStorage()).isFalse();
        assertThatThrownBy(() -> adapter.store(null,null,null)).isInstanceOf(ServiceUnavailableException.class).hasMessage("OCR image storage is not configured.");
        assertThatThrownBy(() -> adapter.load("",null,null)).isInstanceOf(ServiceUnavailableException.class).hasMessage("OCR image storage is not configured.");
        adapter.delete("  "); verifyNoInteractions(client);
    }
    @Test void invalidConfiguredUploadNeverOpensOrWritesAnObject() throws Exception {
        var file=mock(MultipartFile.class); when(file.isEmpty()).thenReturn(true);
        assertThatThrownBy(() -> adapter.store(7L,11L,new MultipartLedgerImageContent(file))).isInstanceOf(BadRequestException.class).hasMessage("OCR image storage request is invalid.");
        verify(file,never()).getInputStream(); verifyNoInteractions(client);
    }
    @ParameterizedTest @ValueSource(strings={"client","endpoint","access","secret","buckets"})
    void everyExistingReadinessRequirementRemainsNecessaryWithoutNetwork(String missing) {
        switch(missing) {
            case "client" -> when(clients.getIfAvailable()).thenReturn(null);
            case "endpoint" -> properties.setEndpoint(null);
            case "access" -> properties.setAccessKey(null);
            case "secret" -> properties.setSecretKey(null);
            default -> { properties.setBucket_cloud(null); properties.setBucket_work(null); }
        }
        assertThat(adapter.supportsStorage()).isFalse(); verifyNoInteractions(client);
    }
    @Test void openingInputFailureKeepsTheSameGenericErrorBeforeAnySdkWrite() throws Exception {
        var file=mock(MultipartFile.class); when(file.getOriginalFilename()).thenReturn("receipt.png");
        when(file.getInputStream()).thenThrow(new IOException("private fixture open failure"));
        assertThatThrownBy(() -> adapter.store(7L,11L,new MultipartLedgerImageContent(file))).isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("OCR image storage is unavailable.");
        verifyNoInteractions(client); verify(file,never()).getBytes();
    }
    @Test void inputCloseFailureIsNotMistakenForASuccessfulDurableUpload() throws Exception {
        var stream=new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() throws IOException { throw new IOException("private fixture close failure"); }
        };
        assertThatThrownBy(() -> adapter.store(7L,11L,new MultipartLedgerImageContent(file("receipt.png",null,stream))))
                .isInstanceOf(ServiceUnavailableException.class).hasMessage("OCR image storage is unavailable.");
        verify(client).putObject(any());
    }
    @Test void sdkUploadFailureClosesInputAndKeepsTheGenericStorageError() throws Exception {
        var stream=new TrackedStream(); var file=file("receipt.png",null,stream);
        when(client.putObject(any())).thenThrow(new IOException("private fixture SDK failure"));
        assertThatThrownBy(() -> adapter.store(7L,11L,new MultipartLedgerImageContent(file))).isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("OCR image storage is unavailable.").hasMessageNotContaining("private fixture");
        assertThat(stream.closes).isEqualTo(1); verify(file,never()).getBytes();
    }
    @Test void downloadKeepsHistoryMimeFilenameBytesAndClosesTheSdkResponse() throws Exception {
        var stream=new TrackedStream();
        when(client.getObject(any())).thenReturn(new GetObjectResponse(new okhttp3.Headers.Builder().add("Content-Type","application/json").build(),"cloud-bucket","fixture","stored-key",stream));
        var result=adapter.load("stored-key"," receipt.jpg "," image/jpeg ");
        assertThat(result.bytes()).containsExactly(1,2,3,4); assertThat(result.fileName()).isEqualTo("receipt.jpg");
        assertThat(result.contentType()).isEqualTo("image/jpeg"); assertThat(stream.closes).isEqualTo(1);
        verify(client).getObject(argThat(args -> args.bucket().equals("cloud-bucket")&&args.object().equals("stored-key")));
    }
    @Test void missingDownloadMetadataRetainsTheExistingDefaults() throws Exception {
        when(client.getObject(any())).thenReturn(new GetObjectResponse(new okhttp3.Headers.Builder().build(),"cloud-bucket","fixture","stored-key",new TrackedStream()));
        var result=adapter.load("stored-key",null,null);
        assertThat(result.fileName()).isEqualTo("ocr-image"); assertThat(result.contentType()).isEqualTo("application/octet-stream");
    }
    @Test void downloadReadFailureStillClosesTheResponseAndHidesTheRawCause() throws Exception {
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        var stream=new InputStream() {
            @Override public int read() throws IOException { throw new IOException("private fixture read failure"); }
            @Override public void close() { closed.set(true); }
        };
        when(client.getObject(any())).thenReturn(new GetObjectResponse(new okhttp3.Headers.Builder().build(),"cloud-bucket","fixture","stored-key",stream));
        assertThatThrownBy(() -> adapter.load("stored-key",null,null)).isInstanceOf(BadRequestException.class).hasMessage("Stored OCR image could not be loaded.");
        assertThat(closed).isTrue();
    }
    @Test void blankObjectLoadIsRejectedButBlankDeleteIsANoOp() {
        assertThatThrownBy(() -> adapter.load(" ",null,null)).isInstanceOf(BadRequestException.class).hasMessage("Stored OCR image is missing.");
        adapter.delete(""); verifyNoInteractions(client);
    }
    @Test void nonBlankObjectDeleteKeepsItsExactKeyAndSdkFailureBehavior() throws Exception {
        doThrow(new IOException("private fixture delete failure")).when(client).removeObject(any());
        assertThatThrownBy(() -> adapter.delete(" stored-key ")).isInstanceOf(ServiceUnavailableException.class).hasMessage("OCR image storage is unavailable.");
        verify(client).removeObject(argThat(args -> args.bucket().equals("cloud-bucket")&&args.object().equals(" stored-key ")));
    }
}
