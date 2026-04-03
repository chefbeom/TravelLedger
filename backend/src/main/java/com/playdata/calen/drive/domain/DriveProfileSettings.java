package com.playdata.calen.drive.domain;

import com.playdata.calen.account.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "drive_profile_settings")
@Getter
@Setter
@NoArgsConstructor
public class DriveProfileSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private AppUser user;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false, length = 10)
    private String localeCode = "KO";

    @Column(nullable = false, length = 10)
    private String regionCode = "KR";

    @Column(nullable = false)
    private boolean marketingOptIn = true;

    @Column(nullable = false)
    private boolean privateProfile = false;

    @Column(nullable = false)
    private boolean emailNotification = true;

    @Column(nullable = false)
    private boolean securityNotification = true;

    @Column(length = 600)
    private String profileImagePath;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
