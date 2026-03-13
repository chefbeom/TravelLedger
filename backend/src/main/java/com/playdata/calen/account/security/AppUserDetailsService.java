package com.playdata.calen.account.security;

import com.playdata.calen.account.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        return appUserRepository.findByLoginId(username)
                .filter(user -> user.isActive())
                .map(AppUserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("사용자 계정을 찾을 수 없습니다."));
    }
}
