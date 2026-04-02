# OCI Redis 2서버 설정 가이드

이 문서는 OCI에서 Redis A(캐시 서버), Redis B(상태/큐 서버)를 각각 별도 인스턴스로 띄우는 기준입니다.

## 파일

- `deploy/oci/redis/docker-compose.redis.cache.yml`
- `deploy/oci/redis/docker-compose.redis.state.yml`
- `deploy/oci/redis/redis-cache.conf`
- `deploy/oci/redis/redis-state.conf`
- `deploy/oci/redis/.env.redis.cache.example`
- `deploy/oci/redis/.env.redis.state.example`

## 역할

- Redis A
  - 캐시 전용
  - 역지오코딩 캐시
  - 조회 응답 캐시
  - 권장 정책: `allkeys-lru`
- Redis B
  - 상태/큐/락 전용
  - 로그인 제한
  - 백업/복구 락
  - 비동기 작업 상태
  - 권장 정책: `noeviction`

## Redis A 실행

```bash
cd ~/calen/deploy/oci/redis
cp .env.redis.cache.example .env.redis.cache
docker compose --env-file .env.redis.cache -f docker-compose.redis.cache.yml up -d
```

## Redis B 실행

```bash
cd ~/calen/deploy/oci/redis
cp .env.redis.state.example .env.redis.state
docker compose --env-file .env.redis.state -f docker-compose.redis.state.yml up -d
```

## 확인

```bash
cd ~/calen/deploy/oci/redis
docker compose --env-file .env.redis.cache -f docker-compose.redis.cache.yml ps
docker compose --env-file .env.redis.state -f docker-compose.redis.state.yml ps
```

## 보안

- `6379`는 메인 서버 private IP만 허용
- public 전체 개방 금지
- `REDIS_PASSWORD`는 각 서버별로 다르게 설정
- Redis A/B 모두 private IP bind 기준으로 운영
