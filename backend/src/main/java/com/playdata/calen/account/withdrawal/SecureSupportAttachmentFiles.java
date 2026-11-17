package com.playdata.calen.account.withdrawal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Delete exactly one known file through descriptor-relative, no-symlink traversal. */
@Component
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class SecureSupportAttachmentFiles {
    private static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
    private static final Path MARKER = Path.of(".calen-support-storage-id");
    private static final Set<OpenOption> READ_NOFOLLOW = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    @Value("${app.support.attachment-storage-path:${user.dir}/uploads/support-inquiries}") private String storagePath;
    @Value("${app.support.attachment-storage-id:}") private String storageId;

    public Outcome deleteExact(SupportAttachmentTarget target) {
        if (target == null || !target.valid()) return Outcome.MANUAL_REVIEW;
        String configuredId = storageId == null ? "" : storageId.trim();
        if (!configuredId.matches("[A-Za-z0-9_-]{16,80}") || !configuredId.equals(target.storageId())) return Outcome.WRONG_STORAGE;
        if (storagePath == null || storagePath.isBlank()) return Outcome.MANUAL_REVIEW;
        List<DirectoryStream<Path>> opened = new ArrayList<>();
        try {
            Path root = Path.of(storagePath).toAbsolutePath().normalize();
            if (root.getRoot() == null || root.getNameCount() == 0) return Outcome.MANUAL_REVIEW;
            DirectoryStream<Path> first = Files.newDirectoryStream(root.getRoot());
            opened.add(first);
            if (!(first instanceof SecureDirectoryStream<Path> current)) return Outcome.UNSUPPORTED;
            for (Path segment : root) {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                opened.add(next);
                current = next;
            }
            return deleteFromOpenedRoot(current, target, configuredId);
        } catch (NoSuchFileException exception) {
            // Absence may mean a different mount or a previous unconfirmed delete; do not claim completion.
            return Outcome.MISSING;
        } catch (IOException | SecurityException | IllegalArgumentException exception) {
            return Outcome.RETRY;
        } finally {
            for (int i = opened.size() - 1; i >= 0; i--) {
                try { opened.get(i).close(); } catch (IOException ignored) { /* no sensitive path in logs */ }
            }
        }
    }

    Outcome deleteFromOpenedRoot(SecureDirectoryStream<Path> root, SupportAttachmentTarget target, String expectedId) throws IOException {
        if (root == null || target == null || !target.valid() || !target.storageId().equals(expectedId)) return Outcome.MANUAL_REVIEW;
        if (!markerMatches(root, expectedId)) return Outcome.WRONG_STORAGE;
        String[] parts = target.path().split("/", -1);
        try (SecureDirectoryStream<Path> user = root.newDirectoryStream(Path.of(parts[0]), LinkOption.NOFOLLOW_LINKS)) {
            Path leaf = Path.of(parts[1]);
            BasicFileAttributes before = attributes(user, leaf);
            if (!before.isRegularFile() || before.isSymbolicLink() || before.fileKey() == null
                    || before.size() < 0 || before.size() > MAX_FILE_BYTES) return Outcome.MANUAL_REVIEW;
            MessageDigest digest = sha256();
            long bytes = 0;
            try (SeekableByteChannel channel = user.newByteChannel(leaf, READ_NOFOLLOW)) {
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                for (int count; (count = channel.read(buffer)) != -1;) {
                    if (count == 0) return Outcome.RETRY;
                    bytes += count;
                    if (bytes > MAX_FILE_BYTES) return Outcome.MANUAL_REVIEW;
                    buffer.flip(); digest.update(buffer); buffer.clear();
                }
            }
            BasicFileAttributes after = attributes(user, leaf);
            if (!after.isRegularFile() || !before.fileKey().equals(after.fileKey())
                    || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || bytes != before.size()
                    || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(target.sha256()))) return Outcome.MANUAL_REVIEW;
            user.deleteFile(leaf);
            return Outcome.DELETED;
        }
    }

    private boolean markerMatches(SecureDirectoryStream<Path> root, String expected) throws IOException {
        BasicFileAttributes marker = attributes(root, MARKER);
        if (!marker.isRegularFile() || marker.isSymbolicLink() || marker.size() > 128) return false;
        try (SeekableByteChannel channel = root.newByteChannel(MARKER, READ_NOFOLLOW)) {
            ByteBuffer buffer = ByteBuffer.allocate(129);
            while (buffer.hasRemaining()) {
                int count = channel.read(buffer);
                if (count == -1) break;
                if (count == 0) throw new IOException("Storage marker could not be read.");
            }
            if (!buffer.hasRemaining()) return false;
            buffer.flip();
            return expected.equals(StandardCharsets.UTF_8.decode(buffer).toString().trim());
        }
    }

    private BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("File attributes unavailable.");
        return view.readAttributes();
    }

    private MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable.", exception); }
    }

    public enum Outcome { DELETED, MISSING, WRONG_STORAGE, UNSUPPORTED, MANUAL_REVIEW, RETRY }
}
