package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class SecureSupportAttachmentFilesTest {
    private static final String ID = "test_storage_identifier_001";
    @TempDir Path root;
    @TempDir Path outside;

    @Test void unsupportedFilesystemOrWrongStorageNeverDeletesAnything() throws Exception {
        Files.writeString(root.resolve(".calen-support-storage-id"), ID);
        Path folder = Files.createDirectory(root.resolve("user-7"));
        Path file = folder.resolve(UUID.randomUUID() + ".png"); Files.writeString(file, "private photo");
        var target = target(file, "different_storage_identifier");
        assertThat(files().deleteExact(target)).isEqualTo(SecureSupportAttachmentFiles.Outcome.WRONG_STORAGE);
        assertThat(Files.exists(file)).isTrue();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root.getRoot())) {
            if (!(stream instanceof SecureDirectoryStream<?>)) {
                assertThat(files().deleteExact(target(file, ID))).isEqualTo(SecureSupportAttachmentFiles.Outcome.UNSUPPORTED);
                assertThat(Files.exists(file)).isTrue();
            }
        }
    }

    @Test void exactDeletionRequiresMatchingDigestAndPreservesOtherFiles() throws Exception {
        assumeSecureFilesystem();
        Files.writeString(root.resolve(".calen-support-storage-id"), ID);
        Path folder = Files.createDirectory(root.resolve("user-7"));
        Path file = folder.resolve(UUID.randomUUID() + ".png"); Files.writeString(file, "private photo");
        Path other = folder.resolve(UUID.randomUUID() + ".png"); Files.writeString(other, "keep me");
        var wrongDigest = new SupportAttachmentTarget(7L, "user-7/" + file.getFileName(), ID, "0".repeat(64));
        assertThat(files().deleteExact(wrongDigest)).isEqualTo(SecureSupportAttachmentFiles.Outcome.MANUAL_REVIEW);
        assertThat(Files.exists(file)).isTrue();
        var correct = target(file, ID);
        assertThat(files().deleteExact(correct)).isEqualTo(SecureSupportAttachmentFiles.Outcome.DELETED);
        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.readString(other)).isEqualTo("keep me");
        // Missing does not imply a previously unconfirmed delete was successful.
        assertThat(files().deleteExact(correct)).isEqualTo(SecureSupportAttachmentFiles.Outcome.MISSING);
    }

    @Test void symbolicUserDirectoryIsNeverFollowed() throws Exception {
        assumeSecureFilesystem();
        Files.writeString(root.resolve(".calen-support-storage-id"), ID);
        Path file = outside.resolve(UUID.randomUUID() + ".png"); Files.writeString(file, "other user's photo");
        try { Files.createSymbolicLink(root.resolve("user-7"), outside); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) { assumeTrue(false); }
        assertThat(files().deleteExact(target(file, ID))).isNotEqualTo(SecureSupportAttachmentFiles.Outcome.DELETED);
        assertThat(Files.readString(file)).isEqualTo("other user's photo");
    }

    private void assumeSecureFilesystem() throws Exception {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root.getRoot())) {
            assumeTrue(stream instanceof SecureDirectoryStream<?>);
        }
    }

    private SecureSupportAttachmentFiles files() {
        var files = new SecureSupportAttachmentFiles();
        ReflectionTestUtils.setField(files, "storagePath", root.toString());
        ReflectionTestUtils.setField(files, "storageId", ID);
        return files;
    }

    private SupportAttachmentTarget target(Path file, String storageId) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        return new SupportAttachmentTarget(7L, "user-7/" + file.getFileName(), storageId, digest);
    }
}
