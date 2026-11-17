package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SecureSupportAttachmentFilesProtocolTest {
    private static final String ID = "test_storage_identifier_001";
    private static final byte[] CONTENT = "synthetic file contents".getBytes(StandardCharsets.UTF_8);
    private record Fixture(SecureDirectoryStream<Path> root, SecureDirectoryStream<Path> user,
                           BasicFileAttributes fileAttributes, Path leaf, SupportAttachmentTarget target) {}

    @Test void deletesOnlyTheExactVerifiedFileAfterReadingMarkerAndDigest() throws Exception {
        var f = fixture(ID);
        assertThat(new SecureSupportAttachmentFiles().deleteFromOpenedRoot(f.root(), f.target(), ID))
                .isEqualTo(SecureSupportAttachmentFiles.Outcome.DELETED);
        verify(f.root()).newDirectoryStream(Path.of("user-7"), LinkOption.NOFOLLOW_LINKS);
        verify(f.user()).deleteFile(f.leaf());
        verify(f.user()).close();
    }

    @Test void mismatchedStorageMarkerPreventsOpeningUserDirectory() throws Exception {
        var f = fixture("different_storage_identifier");
        assertThat(new SecureSupportAttachmentFiles().deleteFromOpenedRoot(f.root(), f.target(), ID))
                .isEqualTo(SecureSupportAttachmentFiles.Outcome.WRONG_STORAGE);
        verify(f.root(), never()).newDirectoryStream(any(), any(LinkOption.class));
        verify(f.user(), never()).deleteFile(any());
    }

    @Test void digestMismatchOrChangedFileIdentityPreventsDeletion() throws Exception {
        var wrongHash = fixture(ID);
        var target = new SupportAttachmentTarget(7L, wrongHash.target().path(), ID, "0".repeat(64));
        assertThat(new SecureSupportAttachmentFiles().deleteFromOpenedRoot(wrongHash.root(), target, ID))
                .isEqualTo(SecureSupportAttachmentFiles.Outcome.MANUAL_REVIEW);
        verify(wrongHash.user(), never()).deleteFile(any());

        var changed = fixture(ID);
        when(changed.fileAttributes().fileKey()).thenReturn("first-inode", "first-inode", "changed-inode");
        assertThat(new SecureSupportAttachmentFiles().deleteFromOpenedRoot(changed.root(), changed.target(), ID))
                .isEqualTo(SecureSupportAttachmentFiles.Outcome.MANUAL_REVIEW);
        verify(changed.user(), never()).deleteFile(any());
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(String markerValue) throws Exception {
        var root = (SecureDirectoryStream<Path>) mock(SecureDirectoryStream.class);
        var user = (SecureDirectoryStream<Path>) mock(SecureDirectoryStream.class);
        var markerView = mock(BasicFileAttributeView.class); var markerAttrs = mock(BasicFileAttributes.class);
        var fileView = mock(BasicFileAttributeView.class); var fileAttrs = mock(BasicFileAttributes.class);
        Path marker = Path.of(".calen-support-storage-id"); Path leaf = Path.of(UUID.randomUUID() + ".png");
        byte[] markerBytes = markerValue.getBytes(StandardCharsets.UTF_8);
        when(root.getFileAttributeView(marker, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)).thenReturn(markerView);
        when(markerView.readAttributes()).thenReturn(markerAttrs);
        when(markerAttrs.isRegularFile()).thenReturn(true); when(markerAttrs.size()).thenReturn((long) markerBytes.length);
        when(root.newByteChannel(eq(marker), anySet())).thenAnswer(call -> channel(markerBytes));
        when(root.newDirectoryStream(Path.of("user-7"), LinkOption.NOFOLLOW_LINKS)).thenReturn(user);
        when(user.getFileAttributeView(leaf, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)).thenReturn(fileView);
        when(fileView.readAttributes()).thenReturn(fileAttrs);
        when(fileAttrs.isRegularFile()).thenReturn(true); when(fileAttrs.fileKey()).thenReturn("inode");
        when(fileAttrs.size()).thenReturn((long) CONTENT.length); when(fileAttrs.lastModifiedTime()).thenReturn(FileTime.fromMillis(42));
        when(user.newByteChannel(eq(leaf), anySet())).thenAnswer(call -> channel(CONTENT));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
        return new Fixture(root, user, fileAttrs, leaf, new SupportAttachmentTarget(7L, "user-7/" + leaf, ID, digest));
    }

    private SeekableByteChannel channel(byte[] data) throws Exception {
        var channel = mock(SeekableByteChannel.class); var offset = new AtomicInteger();
        when(channel.read(any(ByteBuffer.class))).thenAnswer(call -> {
            ByteBuffer buffer = call.getArgument(0); int start = offset.get();
            if (start == data.length) return -1;
            int size = Math.min(buffer.remaining(), data.length - start);
            buffer.put(data, start, size); offset.addAndGet(size); return size;
        });
        return channel;
    }
}
