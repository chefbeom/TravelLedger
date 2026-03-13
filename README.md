# Calen

Spring Boot + JPA + Tomcat + Vue 기반의 통계 중심 가계부 캘린더 프로젝트입니다.

핵심은 단순 일정 관리가 아니라 다음을 한 화면에서 다루는 것입니다.

- 수입/지출 거래 입력
- 대분류/소분류 카테고리 관리
- 카드, 현금, 포인트 등 결제수단 관리
- 일/주/월/년 단위 비교 통계
- 사용자별 데이터 분리와 로그인 유지

## 구성

- `backend`: Spring Boot WAR 애플리케이션
- `frontend`: Vue 3 + Vite 프론트엔드
- `docs/architecture.md`: 도메인/통계 구조 초안

## 실행

### 백엔드

```powershell
cd C:\Users\Playdata\Desktop\calen\backend
.\gradlew.bat bootRun
```

테스트 실행:

```powershell
cd C:\Users\Playdata\Desktop\calen\backend
.\gradlew.bat test
```

기본 주소: `http://localhost:8080`

### 프론트엔드

```powershell
cd C:\Users\Playdata\Desktop\calen\frontend
npm install
npm run dev
```

기본 주소: `http://localhost:5173`

## 현재 주요 기능

- 거래 등록/수정/삭제
- 사용자별 계정 분리
- 로그인/로그아웃
- "이 PC 기억하기" 자동 로그인
- 월간 캘린더 집계
- 기간 비교 통계
- 카테고리/결제수단 추가 및 비활성화

## 다음 확장 후보

1. MariaDB/MySQL 또는 PostgreSQL 연결
2. 엑셀 업로드/다운로드
3. 반복 거래 자동 등록
4. 월 마감 처리
5. 고급 검색 및 필터
