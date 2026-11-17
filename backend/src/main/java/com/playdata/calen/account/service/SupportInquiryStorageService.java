package com.playdata.calen.account.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class SupportInquiryStorageService {

    private static final long MAX_ATTACHMENT_SIZE_BYTES = 5L * 1024L * 1024L;
    private static final Map<String, String> ALLOWED_CONTENT_TYPES_BY_EXTENSION = Map.of(
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".gif", "image/gif",
            ".webp", "image/webp",
            ".bmp", "image/bmp"
    );

    @Value("${app.support.attachment-storage-path:${user.dir}/uploads/support-inquiries}")
    private String attachmentStoragePath;

    /** Optional until an operator provisions and verifies a dedicated storage marker. */
    @Value("${app.support.attachment-storage-id:}")
    private String attachmentStorageId;

    private Path rootPath;

    @PostConstruct
    void initialize() {
        rootPath = Paths.get(attachmentStoragePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootPath);
        } catch (IOException exception) {
            throw new IllegalStateException("문의 첨부 이미지를 저장할 폴더를 만들 수 없습니다.", exception);
        }
    }

    public StoredSupportAttachment store(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }

        if (file.getSize() > MAX_ATTACHMENT_SIZE_BYTES) {
            throw new BadRequestException("Support attachment must be 5MB or smaller.");
        }

        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment");
        String extension = extractExtension(originalFileName);
        String contentType = resolveAllowedContentType(extension, file.getContentType());
        validateBinarySignature(file, contentType);
        String verifiedStorageId = verifiedStorageId();

        Path userDirectory = rootPath.resolve("user-" + userId).normalize();
        if (!userDirectory.startsWith(rootPath)) {
            throw new BadRequestException("이미지 저장 경로를 만들 수 없습니다.");
        }

        try {
            Files.createDirectories(userDirectory);
            String storedFileName = UUID.randomUUID() + extension;
            Path targetPath = userDirectory.resolve(storedFileName).normalize();
            if (!targetPath.startsWith(rootPath)) {
                throw new BadRequestException("이미지 저장 경로를 만들 수 없습니다.");
            }

            boolean created = false;
            MessageDigest digest = sha256();
            try (InputStream inputStream = new DigestInputStream(file.getInputStream(), digest)) {
                try (OutputStream outputStream = Files.newOutputStream(targetPath,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    created = true;
                    inputStream.transferTo(outputStream);
                }
            } catch (IOException | RuntimeException failure) {
                if (created) {
                    try { Files.deleteIfExists(targetPath); } catch (IOException | SecurityException ignored) { /* retry requires operator review */ }
                }
                throw failure;
            }

            String relativeStoragePath = rootPath.relativize(targetPath).toString().replace('\\', '/');
            return new StoredSupportAttachment(
                    relativeStoragePath,
                    originalFileName,
                    contentType,
                    file.getSize(),
                    verifiedStorageId,
                    HexFormat.of().formatHex(digest.digest())
            );
        } catch (IOException exception) {
            throw new IllegalStateException("문의 첨부 이미지를 저장할 수 없습니다.", exception);
        }
    }

    private String verifiedStorageId() {
        String id = attachmentStorageId == null ? "" : attachmentStorageId.trim();
        if (id.isEmpty()) return null;
        if (!id.matches("[A-Za-z0-9_-]{16,80}")) throw new IllegalStateException("문의 첨부 저장소 식별 설정이 잘못되었습니다.");
        Path marker = rootPath.resolve(".calen-support-storage-id");
        try {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("문의 첨부 저장소 식별을 확인할 수 없습니다.");
            }
            try (InputStream input = Files.newInputStream(marker, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                byte[] value = input.readNBytes(129);
                if (value.length > 128 || !id.equals(new String(value, StandardCharsets.UTF_8).trim())) {
                    throw new IllegalStateException("문의 첨부 저장소 식별을 확인할 수 없습니다.");
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("문의 첨부 저장소 식별을 확인할 수 없습니다.");
        }
        return id;
    }

    private MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable.", exception); }
    }

    public Resource loadAsResource(String storedPath) {
        if (!StringUtils.hasText(storedPath)) {
            throw new NotFoundException("첨부 이미지를 찾을 수 없습니다.");
        }

        try {
            Path resolvedPath = rootPath.resolve(storedPath).normalize();
            if (!resolvedPath.startsWith(rootPath) || !Files.exists(resolvedPath)) {
                throw new NotFoundException("첨부 이미지를 찾을 수 없습니다.");
            }

            Resource resource = new UrlResource(resolvedPath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new NotFoundException("첨부 이미지를 읽을 수 없습니다.");
            }
            return resource;
        } catch (IOException exception) {
            throw new NotFoundException("첨부 이미지를 읽을 수 없습니다.");
        }
    }

    public void deleteQuietly(String storedPath) {
        if (!StringUtils.hasText(storedPath)) {
            return;
        }

        try {
            Path resolvedPath = rootPath.resolve(storedPath).normalize();
            if (resolvedPath.startsWith(rootPath)) {
                Files.deleteIfExists(resolvedPath);
            }
        } catch (IOException ignored) {
            // Keep delete best-effort only.
        }
    }

    private String resolveAllowedContentType(String extension, String rawContentType) {
        String expectedContentType = ALLOWED_CONTENT_TYPES_BY_EXTENSION.get(extension);
        if (expectedContentType == null) {
            throw new BadRequestException("Unsupported support attachment image format.");
        }
        String normalizedContentType = rawContentType == null ? "" : rawContentType.trim().toLowerCase(Locale.ROOT);
        if (!expectedContentType.equals(normalizedContentType)) {
            throw new BadRequestException("Support attachment extension and content type must match.");
        }
        return expectedContentType;
    }

    private void validateBinarySignature(MultipartFile file, String contentType) {
        byte[] header = new byte[16];
        int bytesRead;
        try (InputStream inputStream = file.getInputStream()) {
            bytesRead = inputStream.read(header);
        } catch (IOException exception) {
            throw new BadRequestException("Support attachment content could not be inspected.");
        }
        if (bytesRead <= 0 || !matchesSignature(header, bytesRead, contentType)) {
            throw new BadRequestException("Support attachment binary content does not match its image type.");
        }
    }

    private boolean matchesSignature(byte[] header, int bytesRead, String contentType) {
        return switch (contentType) {
            case "image/png" -> bytesRead >= 8
                    && unsigned(header[0]) == 0x89
                    && header[1] == 0x50
                    && header[2] == 0x4E
                    && header[3] == 0x47
                    && header[4] == 0x0D
                    && header[5] == 0x0A
                    && header[6] == 0x1A
                    && header[7] == 0x0A;
            case "image/jpeg" -> bytesRead >= 3
                    && unsigned(header[0]) == 0xFF
                    && unsigned(header[1]) == 0xD8
                    && unsigned(header[2]) == 0xFF;
            case "image/gif" -> bytesRead >= 4
                    && header[0] == 0x47
                    && header[1] == 0x49
                    && header[2] == 0x46
                    && header[3] == 0x38;
            case "image/webp" -> bytesRead >= 12
                    && header[0] == 0x52
                    && header[1] == 0x49
                    && header[2] == 0x46
                    && header[3] == 0x46
                    && header[8] == 0x57
                    && header[9] == 0x45
                    && header[10] == 0x42
                    && header[11] == 0x50;
            case "image/bmp" -> bytesRead >= 2
                    && header[0] == 0x42
                    && header[1] == 0x4D;
            default -> false;
        };
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }
    private String extractExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            throw new BadRequestException("이미지 확장자를 확인할 수 없습니다.");
        }
        return fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    public record StoredSupportAttachment(
            String storagePath,
            String originalFileName,
            String contentType,
            long size,
            String storageId,
            String sha256
    ) {
        public StoredSupportAttachment(String storagePath, String originalFileName, String contentType, long size) {
            this(storagePath, originalFileName, contentType, size, null, null);
        }
        @Override public String toString() { return "StoredSupportAttachment[redacted]"; }
    }
}
