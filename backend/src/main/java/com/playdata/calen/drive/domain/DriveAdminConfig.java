package com.playdata.calen.drive.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "drive_admin_config")
@Getter
@Setter
@NoArgsConstructor
public class DriveAdminConfig {

    public static final long SINGLETON_ID = 1L;
    public static final long DEFAULT_PROVIDER_CAPACITY_BYTES = 200L * 1024L * 1024L * 1024L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(nullable = false)
    private long providerCapacityBytes = DEFAULT_PROVIDER_CAPACITY_BYTES;
}
