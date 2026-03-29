package com.playdata.calen.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String loginId;

    @Column(nullable = false, length = 80)
    private String displayName;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(length = 100)
    private String secondaryPinHash;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AppUserRole role = AppUserRole.USER;

    @Column(nullable = false)
    private boolean active = true;
}
