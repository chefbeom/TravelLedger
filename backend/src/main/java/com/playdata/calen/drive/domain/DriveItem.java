package com.playdata.calen.drive.domain;

import com.playdata.calen.account.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "drive_items")
@Getter
@Setter
@NoArgsConstructor
public class DriveItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private DriveItem parent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DriveItemType itemType = DriveItemType.FILE;

    @Column(nullable = false, length = 255)
    private String originalName;

    @Column(nullable = false, length = 40)
    private String extension = "";

    @Column(nullable = false, length = 255)
    private String storedName;

    @Column(length = 600)
    private String storagePath;

    @Column(nullable = false)
    private long fileSize = 0L;

    @Column(nullable = false)
    private boolean lockedFile = false;

    @Column(nullable = false)
    private boolean sharedFile = false;

    @Column(nullable = false)
    private boolean trashed = false;

    private LocalDateTime deletedAt;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    @Column(nullable = false)
    private LocalDateTime lastModifiedAt;

    private LocalDateTime lastAccessedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (uploadedAt == null) {
            uploadedAt = now;
        }
        if (lastModifiedAt == null) {
            lastModifiedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        lastModifiedAt = LocalDateTime.now();
    }

    public boolean isFolder() {
        return itemType == DriveItemType.FOLDER;
    }

    public boolean isFile() {
        return itemType == DriveItemType.FILE;
    }

    public void markTrashed() {
        this.trashed = true;
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.trashed = false;
        this.deletedAt = null;
    }
}
