# 아키텍처 초안

## 1. 요구사항 해석

이 서비스는 일반 캘린더보다 아래 3개 축이 핵심입니다.

1. 날짜별 거래 입력
2. 유연한 분류 체계 관리
3. 강한 통계/비교 분석

즉, 중심 엔티티는 `일정`이 아니라 `거래(LedgerEntry)`입니다.

## 2. 추천 아키텍처

### 백엔드

- Spring Boot + Spring MVC
- Spring Data JPA
- WAR 패키징
- 내장/외장 Tomcat 모두 대응
- 개발용 DB는 H2, 운영 DB는 MySQL/MariaDB/PostgreSQL 전환 가능

### 프론트엔드

- Vue 3 + Vite
- 단일 페이지 대시보드
- 섹션 구성:
  - 요약 KPI
  - 기간 비교 통계
  - 캘린더 집계
  - 엑셀형 입력 시트
  - 카테고리/결제수단 관리

## 3. 도메인 모델

### LedgerEntry

실제 수입/지출 데이터입니다.

- 거래일
- 제목
- 메모
- 금액
- 수입/지출 구분
- 대분류
- 소분류
- 결제수단

### CategoryGroup

대분류입니다.

예시:

- 지출 > 식비
- 지출 > 교통
- 수입 > 급여

### CategoryDetail

소분류입니다.

예시:

- 식비 > 군것질
- 식비 > 외식
- 급여 > 본급

### PaymentMethod

결제수단입니다.

예시:

- 카드 > 신한카드
- 카드 > 우리카드
- 현금
- 포인트

## 4. 통계 설계 원칙

통계가 핵심이므로, 입력 구조도 통계 친화적으로 구성합니다.

### 집계 축

- 기간별: 일 / 주 / 월 / 년
- 유형별: 수입 / 지출 / 순이익
- 카테고리별: 대분류 / 소분류
- 결제수단별: 카드 / 현금 / 포인트 / 카드사별

### 주요 응답 형태

- 기간 합계
- 기간 비교
- 일자별 캘린더 합계
- 카테고리 구성비
- 결제수단 구성비

## 5. 주요 API 방향

### 거래

- `GET /api/entries?from=2026-03-01&to=2026-03-31`
- `POST /api/entries`

### 카테고리

- `GET /api/categories`
- `POST /api/categories/groups`
- `POST /api/categories/details`
- `DELETE /api/categories/groups/{id}`
- `DELETE /api/categories/details/{id}`

### 결제수단

- `GET /api/payment-methods`
- `POST /api/payment-methods`
- `DELETE /api/payment-methods/{id}`

### 통계

- `GET /api/statistics/overview`
- `GET /api/statistics/calendar`
- `GET /api/statistics/category-breakdown`
- `GET /api/statistics/payment-breakdown`
- `GET /api/statistics/compare`
- `GET /api/dashboard`

## 6. 확장 포인트

- 사용자별 데이터 분리
- 예산 대비 실적
- 반복 거래 자동 등록
- 엑셀 업로드/다운로드
- 월별 마감 처리
- 고급 필터: 태그, 프로젝트, 거래처

## 7. 구현 우선순위

1. 거래 입력/조회
2. 대분류/소분류/결제수단 관리
3. 기간별 비교 통계
4. 캘린더 집계 화면
5. 엑셀 업로드/다운로드
