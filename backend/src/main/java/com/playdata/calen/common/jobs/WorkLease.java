package com.playdata.calen.common.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_work_leases", indexes = @Index(name = "idx_work_leases_expiry", columnList = "expires_at_epoch_millis"))
public class WorkLease {
    @Id @Column(name = "lease_key", length = 240) private String key;
    @Column(name = "owner_token", nullable = false, length = 36) private String ownerToken;
    @Column(name = "expires_at_epoch_millis", nullable = false) private long expiresAtEpochMillis;
    protected WorkLease() { }
}
