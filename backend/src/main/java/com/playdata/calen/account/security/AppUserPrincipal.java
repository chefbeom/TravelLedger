package com.playdata.calen.account.security;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record AppUserPrincipal(
        Long userId,
        String loginId,
        String displayName,
        String passwordHash,
        AppUserRole role,
        boolean active
) implements UserDetails {

    public static AppUserPrincipal from(AppUser user) {
        AppUserRole normalizedRole = user.getRole() != null ? user.getRole() : AppUserRole.USER;
        return new AppUserPrincipal(
                user.getId(),
                user.getLoginId(),
                user.getDisplayName(),
                user.getPasswordHash(),
                normalizedRole,
                user.isActive()
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_" + role.name())
        );
    }

    public boolean isAdmin() {
        return role == AppUserRole.ADMIN;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return loginId;
    }

    @Override
    public boolean isAccountNonExpired() {
        return active;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return active;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
