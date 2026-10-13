package com.playdata.calen.recordmap.service;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.recordmap.domain.*;
import com.playdata.calen.recordmap.dto.RecordMapDtos.*;
import com.playdata.calen.recordmap.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RecordMapShareService {
    private final RecordMapShareRepository shares;
    private final PlaceCollectionRepository collections;
    private final AppUserService users;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public ShareView create(Long owner, Long collectionId, ShareRequest request) {
        users.getRequiredUser(owner);
        PlaceCollection collection = collections.findByIdAndOwnerId(collectionId, owner).orElseThrow(RecordMapShareService::unavailable);
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RecordMapShare share = new RecordMapShare();
        share.setOwnerId(owner); share.setCollection(collection); share.setTokenHash(hash(token));
        share.setIncludeVisits(request.includeVisits());
        share.setExpiresAt(LocalDateTime.now().plusDays(request.expiresInDays()));
        shares.save(share);
        return view(share, token);
    }

    public List<ShareView> list(Long owner, Long collectionId) {
        collections.findByIdAndOwnerId(collectionId, owner).orElseThrow(RecordMapShareService::unavailable);
        return shares.findAllByOwnerIdAndCollectionIdOrderByCreatedAtDesc(owner, collectionId).stream().map(s -> view(s, null)).toList();
    }

    @Transactional
    public void revoke(Long owner, Long id) {
        RecordMapShare share = shares.findByIdAndOwnerId(id, owner).orElseThrow(RecordMapShareService::unavailable);
        share.setActive(false);
    }

    public RecordMapShare resolve(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        RecordMapShare share = shares.findByTokenHashAndActiveTrue(hash(token)).orElseThrow(RecordMapShareService::unavailable);
        if (!share.getExpiresAt().isAfter(LocalDateTime.now())) throw unavailable();
        users.getRequiredUser(share.getOwnerId());
        return share;
    }

    public void requireMediaScope(RecordMapShare share, RecordPlace place) {
        if (!share.isIncludeVisits() || !Objects.equals(share.getOwnerId(), place.getOwnerId())
            || place.getCollections().stream().noneMatch(c -> Objects.equals(c.getId(), share.getCollection().getId()))) throw unavailable();
    }

    private ShareView view(RecordMapShare share, String token) {
        return new ShareView(share.getId(), share.isActive() && share.getExpiresAt().isAfter(LocalDateTime.now()),
            share.isIncludeVisits(), share.getExpiresAt(), token);
    }
    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static NotFoundException unavailable() { return new NotFoundException("기록 지도 또는 공유 자료를 찾을 수 없습니다."); }
}
