package com.playdata.calen.travel.service;

import com.playdata.calen.common.cache.RedisCacheService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TravelMyMapPhotoClusterSnapshotService {

    private static final String SNAPSHOT_CACHE_KEY_PREFIX = "travel:mymap:photo-clusters:";

    private final RedisCacheService redisCacheService;

    @Value("${app.travel.photo-cluster-snapshot-ttl-seconds:21600}")
    private long snapshotCacheTtlSeconds;

    public TravelMyMapPhotoClusterSnapshot get(Long userId) {
        if (userId == null) {
            return null;
        }
        return redisCacheService.get(buildCacheKey(userId), TravelMyMapPhotoClusterSnapshot.class);
    }

    public TravelMyMapPhotoClusterSnapshot get(Long userId, long generation) {
        VersionedSnapshot cached = redisCacheService.get(buildCacheKey(userId), VersionedSnapshot.class);
        return cached != null && cached.generation() == generation ? cached.snapshot() : null;
    }

    public void save(Long userId, long generation, TravelMyMapPhotoClusterSnapshot snapshot) {
        Duration ttl = resolveSnapshotTtl();
        if (!ttl.isZero() && !ttl.isNegative()) {
            redisCacheService.set(buildCacheKey(userId), new VersionedSnapshot(generation, snapshot), ttl);
        }
    }

    public record VersionedSnapshot(long generation, TravelMyMapPhotoClusterSnapshot snapshot) { }

    public void save(Long userId, TravelMyMapPhotoClusterSnapshot snapshot) {
        if (userId == null || snapshot == null) {
            return;
        }
        Duration ttl = resolveSnapshotTtl();
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        redisCacheService.delete(buildCacheKey(userId));
        redisCacheService.set(buildCacheKey(userId), snapshot, ttl);
    }

    public void delete(Long userId) {
        if (userId == null) {
            return;
        }
        redisCacheService.delete(buildCacheKey(userId));
    }

    private Duration resolveSnapshotTtl() {
        if (snapshotCacheTtlSeconds <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(snapshotCacheTtlSeconds);
    }

    private String buildCacheKey(Long userId) {
        return SNAPSHOT_CACHE_KEY_PREFIX + userId;
    }
}
