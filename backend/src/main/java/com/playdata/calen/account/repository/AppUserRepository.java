package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.AppUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    boolean existsByLoginId(String loginId);

    List<AppUser> findAllByActiveTrueOrderByDisplayNameAscIdAsc();

    Optional<AppUser> findByLoginId(String loginId);
}
