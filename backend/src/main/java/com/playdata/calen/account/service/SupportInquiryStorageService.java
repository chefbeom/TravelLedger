package com.playdata.calen.account.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
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

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp");

    @Value("${app.support.attachment-storage-path:${user.dir}/uploads/support-inquiries}")
    private String attachmentStoragePath;

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

        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment");
        String extension = extractExtension(originalFileName);
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase(Locale.ROOT) : "";

        if (!contentType.startsWith("image/")) {
            throw new BadRequestException("첨부 파일은 이미지여야 합니다.");
        }
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BadRequestException("지원하지 않는 이미지 형식입니다.");
        }

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

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            String relativeStoragePath = rootPath.relativize(targetPath).toString().replace('\\', '/');
            return new StoredSupportAttachment(
                    relativeStoragePath,
                    originalFileName,
                    contentType,
                    file.getSize()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("문의 첨부 이미지를 저장할 수 없습니다.", exception);
        }
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
            long size
    ) {
    }
}
