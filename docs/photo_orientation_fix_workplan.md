# 사진 방향(세로 사진이 가로로 저장됨) 수정 + '사진 추가' 버튼 미표시 대응 작업 지시서

작성: 2026-11-22 · 기준 커밋 `0f6f08a4`(origin/main과 같음) · 대상: Vue 3 프론트(`frontend/`) + Spring Boot 백엔드(`backend/`)

> **먼저 읽을 것 — 사용자 화면은 구버전이다.** 사용자가 본 '기록 지도' 패널(머리에 `0개 핀` + `장소/숙소/음식점/관광지/이동/새 기록`, 설명 "지도를 누르면 먼저 임시 핀이 생기고, 그 핀을 한 번 더 눌렀을 때만 기록 입력 모달이 열립니다.")은 커밋 `3fac8532` 이하 빌드의 화면이다. 현재 HEAD에는 같은 자리에 `사진 추가` 버튼이 있고 라이트·다크, 데스크톱·모바일 모두 보인다(1.2절). **배포 서버의 프론트 이미지를 다시 빌드하고 브라우저 탭을 새로고침해야** 사용자가 버튼을 보게 된다(4.1절의 확인 방법). 이 문서의 4장 코드 변경은 그 위에 "더 잘 보이게" 하는 보조 작업이다.

---

## 0. 꼭 지킬 규칙

1. **새 npm/Gradle 의존성 금지.** 백엔드에는 이미 `com.drewnoakes:metadata-extractor:2.19.0`(`backend/build.gradle` 40행)이 있고 `ImageThumbnailService`가 이미 쓰고 있다. 별도 EXIF 파서를 만들지 않는다. 프론트는 기존 `exifr`만 쓴다.
2. **DB 스키마 변경·마이그레이션 금지.** 엔티티 필드 추가 금지. 요청 DTO(JSON)에 선택 필드를 추가하는 것은 허용(3.5절).
3. **수정 금지 파일:** `frontend/src/style.css`, `frontend/src/App.vue`, `frontend/src/components/TravelMapPanel.vue`, `frontend/src/components/TravelMyMapClusterPanel.vue`(공통 지도 컴포넌트 — 기존 `#toolbar` 슬롯만 사용).
4. **`frontend/src/lib/api.js`에는 무관한 미커밋 변경(`fetchAdminWithdrawalEmbeddingStatus`, 4줄)이 있다.** 이 파일은 2단계에서 지정한 2곳만 고치고 그 변경은 그대로 둔다. 파일 전체를 덮어쓰지 말고 Edit로 부분 수정한다.
5. **작업 트리의 다른 미커밋 변경(백엔드 AI·탈퇴 관련 파일, `application.yml`, docker-compose, `.env*.example`, 기타 문서 등)을 되돌리거나 커밋에 섞지 않는다.** 커밋할 때는 이 작업에서 만든/고친 파일만 `git add <경로>`로 올린다(`git add -A`/`git add .` 금지). 커밋은 사용자가 요청할 때만.
6. **원본은 원본 바이트 그대로 저장한다(EXIF 포함).** 원본을 브라우저에서 재인코딩하거나 EXIF를 지우는 코드를 추가하지 않는다. 방향은 "보여 주기용 파생물(썸네일·미리보기)"에서만 픽셀에 반영한다.
7. **전역 CSS 대응:** 전역 CSS가 `button`/`.button` 배경·테두리를 `!important`로 강제하고 다크 테마(`:root[data-theme='toss']`) 특이도가 높다. `docs/record_map_ux_workplan.md` 3.6절 규칙(루트 접두사 `:global(html body .app-shell .record-map-hub …)` + `!important`, 선택 상태는 배경 대신 테두리·inset 띠, `border-radius` 기대 금지)과 `frontend/src/components/record-map/recordMapTokens.css`의 `--rm-*` 토큰을 재사용한다.
8. **직전 작업 계약 유지(`docs/travel_photo_add_workplan.md`):** 머리 버튼 이름 `사진 추가`(정확히 이 이름은 머리 버튼 1개만), `새 기록`, 클래스 `.travel-memory-map-panel__actions`, 숨은 파일 입력 `data-photo-picker="list"`, 파일 창은 클릭 핸들러 안에서 **동기적으로** `input.click()`(await 뒤에 열지 않는다).
9. **문서·테스트에 실제 사용자 데이터·로컬 사용자 경로를 쓰지 않는다.** 테스트 이미지는 테스트 안에서 버퍼로 만든다(6장). 스크린샷은 작업자 로컬 임시 폴더에 두고 저장소에 넣지 않는다.
10. `scripts/verify-media-processing-contract.ps1`가 검사하는 문자열(예: `applyExifOrientation`, `createPreparedThumbnails`, `writeToCache`, `loadPreparedThumbnail`, `ensurePreparedThumbnails`, `verifyPreparedThumbnailUploads`, 테스트 메서드 `storesPreparedThumbnailsAlongsideOriginalImage`, `ensurePreparedThumbnailsReportsAlreadyPresentWhenPreparedSetExists`, `ensurePreparedThumbnailsRecreatesMissingPreparedVariant`, `shouldCreatePreparedThumbnailsForConfiguredWidths`, `shouldReuseCachedThumbnailForRepeatedRequests`)의 이름을 바꾸거나 지우지 않는다.

---

## 1. 결론 요약

### 1.1 방향이 사라지는 지점 (재현으로 확정)

**원본은 정상이고, 브라우저가 만들어 올리는 썸네일 4종(pin 96 / mini 240 / preview 480 / detail 960)이 두 번 회전되어 저장된다.** 화면 대부분(지도 핀, 목록, 사진첩, 공유 화면, 통합 달력)이 원본이 아니라 이 썸네일을 쓰므로 "모든 사진이 가로로 저장된다"로 보인다. 라이트박스만 원본을 써서 정상이다.

원인 코드: `frontend/src/lib/mediaPreview.js`
- 115~125행 `loadPreviewImage()`가 `createImageBitmap(file, { imageOrientation: 'none' })`로 디코딩한다. 현재 브라우저(재현: Playwright Chromium 149)는 `'none'`이어도(그리고 `'from-image'`·기본값도) **EXIF Orientation을 이미 적용한 비트맵**을 돌려준다.
- 그런데 256~257행과 263~270행은 그 비트맵을 "회전 전 원시 픽셀"로 간주해 가로·세로를 바꾼 캔버스를 만들고 `drawOrientedPreview()`(127~165행)로 **한 번 더 회전**한다.
- 결과: Orientation 6/8(세로 촬영) → 가로 직사각형(원시 픽셀 기준 180° 회전 = 올바른 모습에서 90° 더 돈 상태), 3 → 원시 픽셀 그대로(거꾸로), 2/4/5/7(미러) → 반전이 상쇄되거나 잘못 겹침.

재현 근거(작업자 로컬 임시 폴더에 결과 보관, 저장소에는 없음):

| 확인 항목 | 방법 | 결과 |
|---|---|---|
| (i) 업로드 바이트 | 임시 e2e에서 '사진 추가' → 저장, `__mock-upload/**` PUT 본문 캡처. 입력: 480×240 원시 픽셀 + Orientation=6 + GPS·날짜 | 원본 PUT 본문 = 입력 파일과 **바이트 완전 동일**, EXIF 유지, 브라우저 디코딩 240×480(세로). 썸네일 PUT: pin 96×48, mini 240×120, preview 480×240, detail 480×240 — **전부 가로**, EXIF 없음, 그림의 위쪽이 오른쪽을 향함 |
| (i') 브라우저 디코딩 동작 | 120×60 원시 + Orientation 1~8 각각 | `createImageBitmap` 기본값·`'none'`·`'from-image'`, `<img>.naturalWidth` 모두 5~8에서 60×120(이미 적용). 클라이언트 썸네일은 1~8 전부 120×60. 표식(원시 좌상단 빨간 사각형) 위치: 1·2·3·4·5·7 → 좌상단, 6·8 → 우하단(= 이중 적용) |
| (i'') 가져오기 대화상자 미리보기 | 같은 Orientation=6 사진 | `createLocalImagePreview()` 결과 160×80(가로) — 미리보기도 틀림 |
| (ii) 서버 썸네일 | 임시 JUnit에서 `ImageThumbnailService.createPreparedThumbnails()`에 400×200 원시 + Orientation 1~8(리틀/빅 엔디언 번갈아) | `resolveExifOrientation()`은 1~8 모두 정확히 읽음. 1·2·3·4·6·8 결과 정확(6·8 → 96×192). **5·7은 회전 없이 96×48**(`applyExifOrientation()` 204~231행에 case 5·7 없음) |
| (iii) 표시 | 원본 `<img>`(CSS `image-orientation` 기본 from-image, `style.css`에도 from-image 지정) vs 업로드된 썸네일 | 원본은 세로로 정상 표시, 썸네일은 옆으로 누운 가로 |

구분 결론:
- **원본 파일(MinIO 원본 객체): 정상.** 업로드 바이트 그대로, EXIF 포함(`api.js` 1424~1443행 `uploadPreparedTravelMediaEntry`가 `preparedEntry.originalFile`을 그대로 PUT).
- **여행 사진의 미리 만든 썸네일(`…/.thumbs/{96,240,480,960}/파일명.jpg`): 틀림.** 클라이언트 재인코딩 결과이며 서버는 검증 없이 그대로 신뢰한다(`TravelMediaStorageService` 960~984행은 키·형식·크기만 확인).
- **내 장소(기록 지도) 사진: 대부분 정상.** 멀티파트로 원본만 올리고(`recordMapApi.js` 22행) 서버가 썸네일을 만든다(`RecordMapMediaService` 37행 → `TravelMediaStorageService.store` → 서버 생성). 단 Orientation 5·7은 서버 버그로 틀림.
- **가족 앨범·드라이브: 서버 생성이므로 5·7만 틀림**(이번 범위에서는 공통 서비스만 고치고 저장된 가족 앨범 썸네일 재생성은 비범위, 7장).
- 영향 시작: 클라이언트 썸네일 업로드가 도입된 `b3d1bc0a`(2026-04-05) 이후 여행 사진 업로드분 중 Orientation ≠ 1인 사진 전부. (휴대폰 세로 사진은 대부분 6.)

### 1.2 "사진 추가 버튼이 안 보임"의 원인

**사용자가 보고 있는 화면은 HEAD보다 오래된 빌드다.**
- 머리의 빠른 버튼(`장소/숙소/음식점/관광지/이동` = `quickMemoryPresets`, 키 general/lodging/food/sightseeing/transit)은 `5b9f3e7b`(2026-06-01 "Improve travel memory quick starts")에서 생기고 `e2477e88`(2026-11-22)에서 지워졌다. 옛 설명 문구는 `c22f7032`부터 `0f6f08a4` 직전까지 있었다. 둘이 함께 있고 `사진 추가`가 없는 마지막 커밋은 **`3fac8532`**(`e2477e88`의 부모). 즉 사용자 화면 = `5b9f3e7b` ~ `3fac8532` 사이 빌드. 그 빌드에는 이 패널에 '사진 추가' 버튼 자체가 없다.
- HEAD 확인(임시 e2e, 목 데이터, `기록 목록·편집`과 `여행 정보 수정 → 사진 · GPS` 두 진입점, 데스크톱 1440×900·모바일 390×844, 라이트·다크): `.travel-memory-map-panel__actions` 안 `사진 추가`가 4가지 조합 모두 `visible`, `opacity 1`, `display flex`.
  - 라이트: 글자 rgb(255,255,255) / 배경 rgb(53,83,61), 대비 8.55.
  - 다크: 글자 rgb(241,244,243) / 배경 rgb(38,48,51), 대비 12.22. **단, 다크에서는 `사진 추가`(primary)와 `새 기록`(secondary)의 배경·글자가 완전히 같아** 주 버튼이 강조되지 않는다(전역 다크 규칙이 덮음).
  - 위치: 데스크톱 y≈675(첫 화면 안), 모바일 y≈1219(첫 화면 아래, 스크롤 필요). 패널이 화면보다 길어 지도를 보도록 스크롤하면 머리 버튼은 화면 밖(고정 앱 헤더 아래)으로 사라지고, 지도 도구 줄에는 사진 버튼이 없으며 지도 아래 안내는 "사진은 위의 ‘사진 추가’를 쓰세요"라고만 한다.
- 구버전이 남을 수 있는 원인(코드 확인 결과, 4.1절 상세): ① 배포 서버에서 `git pull` 후 프론트 이미지를 다시 빌드하지 않음(`docker-compose.oci.app.yml` 198~201행 `frontend`는 `build:`로 서버에서 빌드하므로 `up -d`만으로는 옛 이미지 유지), ② 배포 전부터 열어 둔 탭(SPA라 새로고침 전까지 옛 JS 유지), ③ 저장소 안 `implementation-worktree/`(detached `66171318`, 2026-08-26) 같은 다른 워크트리에서 dev 서버를 띄움 — 그 체크아웃의 `TravelMemoryPanel.vue`에는 옛 빠른 버튼과 옛 문구가 그대로 있다. 서비스워커(`frontend/public/sw.js`)는 내비게이션을 network-first로 받고 nginx가 `index.html`·`sw.js`에 `no-store`를 주므로(`frontend/nginx/default.conf.template`) 정상 온라인 상태에서 서비스워커 때문에 구버전이 고착될 가능성은 낮다.

---

## 2. 현행 업로드·저장·표시 파이프라인 (코드 근거)

### 2.1 여행 기록 사진 (presigned 업로드)

```
파일 선택
 ├─ TravelMyMapWorkspace '사진 추가' / TravelMemoryPanel '사진 추가'(60~74행 pickPhotos/onPhotosPicked) / 기록 편집 대화상자
 ▼
가져오기 대화상자 (useTravelImport.js)
 ├─ 337행 extractPhotoMetadata(file)  ← photoMetadata.js, exifr로 날짜·시간·GPS (정상)
 ├─ 344행 canDecodeImage(file)        ← createImageBitmap(file) 디코딩 가능 여부만
 └─ 348행 createLocalImagePreview(file, 160×120)  ← mediaPreview.js 290~327행 ✗ 이중 회전(미리보기)
   (TravelRecordEditDialog.vue 265행도 같은 함수, 160×160)
 ▼
저장 → uploadTravelMemoryMedia (api.js 1581행) → uploadTravelMediaInternal → uploadTravelMediaWithPresignedUrls (1445행)
 ├─ prepareTravelMediaUploadEntries (1375행)
 │    └─ 1394~1395행 prepareClientImageUpload(sourceFile)  ← mediaPreview.js 236~288행
 │         ├─ resolveImageOrientation: exifr.orientation (정상 값)
 │         ├─ resolveImageGps
 │         ├─ loadPreviewImage: createImageBitmap(file,{imageOrientation:'none'})  ← 이미 회전된 비트맵
 │         └─ 4종 썸네일: 가로·세로 교환 + drawOrientedPreview 재회전  ✗ 이중 회전
 ├─ POST /api/travel/memories/{id}/media/presign  (파일·썸네일 크기 목록)
 │    서버: TravelMediaStorageService.preparePresignedUploads(251행) → createPresignedThumbnailUploads(733행)
 │          썸네일 키 = buildThumbnailStoragePath(666행) = "{원본디렉터리}/.thumbs/{폭}/{원본이름}.jpg|png"
 │          TravelUploadGrantRegistry.recordMedia → TravelUploadGrantPaths.isThumbnail(18~29행) 경로 검증
 ├─ PUT 원본(파일 바이트 그대로, EXIF 유지) + PUT 썸네일 4개 (1424~1443행)
 └─ POST …/media/complete  (1512~1531행, 파일마다 objectKey·thumbnails·gpsLatitude/Longitude)
      서버: completePresignedUpload(268행) → verifyUploadedObject + verifyPreparedThumbnailUploads(960~984행)
            (키·contentType·크기만 확인, 픽셀/방향 검증 없음) → TravelMediaAsset 저장
```

### 2.2 내 장소(기록 지도) 사진 (멀티파트)

`RecordMapWorkspace.vue` 311~316행 `uploadPending` → `recordMapApi.photo`(`frontend/src/lib/recordMapApi.js` 22행, FormData) → `RecordMapController` 84행 → `RecordMapMediaService.upload`(28~49행) → `TravelMediaStorageService.store(owner, 0L, visitId, file)`(81행) → 원본 저장 + `prepareDerivedThumbnailsQuietly`(621~644행) → `ImageThumbnailService.createPreparedThumbnails`(91~134행, 서버가 EXIF 적용) → 같은 `.thumbs/{폭}/` 경로. 대기 중 미리보기는 `URL.createObjectURL(file)`(290행)이라 브라우저가 정상 표시.

### 2.3 서버 썸네일 생성·조회

- `ImageThumbnailService`(`backend/src/main/java/com/playdata/calen/common/media/ImageThumbnailService.java`)
  - `createPreparedThumbnails`(91행): `readSafeImage`(ImageIO, 4천만 화소 제한) → `resolveExifOrientation`(174~191행, metadata-extractor `ExifIFD0Directory.TAG_ORIENTATION`) → `applyExifOrientation`(193~252행: case 2·3·4·6·8만, **5·7 없음**) → 118행 `filter(width -> orientedImage.getWidth() > width)`로 **원본보다 넓은 폭은 만들지 않음**.
  - `createThumbnail`(57~89행): 드라이브용. 임시 폴더 `calen-thumbnail-cache` 캐시, 키 = `resource.getDescription()|contentType|ext|width|length|lastModified`의 SHA-256(335~350행). 방향 로직 버전이 키에 없음.
- `TravelMediaStorageService`(`backend/src/main/java/com/playdata/calen/travel/service/TravelMediaStorageService.java`)
  - `loadPreparedThumbnail`(319행): `PreparedThumbnailProfile.selectWidth(w)` 버킷 경로를 읽기만.
  - `loadThumbnail`(336~350행): 없으면 요청 스레드에서 **동시성 제한 없이** `prepareDerivedThumbnails`(607행: 원본 전체 읽기 → 4종 생성·저장) 후 다시 읽음.
  - `ensurePreparedThumbnails`(352행): 4종이 모두 있으면 ALREADY_PRESENT. `TravelThumbnailBackfillService`(시작 시 + 15분마다, 1회 최대 100건)가 `TravelMediaAsset` 이미지 행을 id 순으로 돌며 호출.
  - `deleteImageWithThumbnailsQuietly`(381~389행): 원본 + 4개 버킷 경로 삭제. 호출처: `TravelAttachmentCleanup` 34행, `TravelMediaStorageAdapter` 54행, `RecordMapMediaService` 40·62행.
- 응답 헤더: `TravelMediaHttpResponses.render`(24~47행) 썸네일은 `Cache-Control: public, max-age=86400`(34행) → **같은 URL이면 브라우저가 하루 동안 옛 썸네일을 씀.** `RecordMapController` 132~140행·`CalendarMapShareController` 53~60행은 `no-store`. 드라이브의 여행 연결 썸네일은 ETag `"{id}-{lastModifiedAt}-travel-thumb"`(`DriveService` 1114행) + `max-age=1일, must-revalidate`(`DriveFileController` 348행) → 내용이 바뀌어도 ETag가 같아 304로 옛 이미지 유지.

### 2.4 표시 지점

| 화면 | 코드 | 쓰는 이미지 |
|---|---|---|
| 여행 지도 사진 핀/클러스터 | `TravelMyMapClusterPanel.vue` 844행 (pin) | 썸네일 ✗ |
| 지도 사진 미리보기 | `TravelMapPhotoPreview.vue` 18행, `TravelMapPanel.vue` 182행 | 썸네일 ✗ (실패 시 원본) |
| 지도 인스펙터 | `TravelMyMapInspectorPanels.vue` 121·167행 (preview) | 썸네일 ✗ |
| 설정 2단계 기록 목록 | `TravelMemoryPanel.vue` 1163행 | 썸네일 ✗ |
| 사진첩 | `TravelMyPhotosWorkspace.vue` 232행 | 썸네일 ✗ |
| 여행 허브 카드 | `TravelHubWorkspace.vue` 2854행 | 썸네일 ✗ |
| 메인 대시보드 | `MainDashboardWorkspace.vue` 553행 | 썸네일 ✗ |
| 공유 지도·전시 | `TravelPublicMapPhotoDetailModal.vue`, `TravelSharedExhibitWorkspace.vue` → `TravelMapLinkController`/`TravelExhibitController` → `TravelMediaHttpResponses` | 썸네일 ✗ |
| 통합 달력 지도 사진 | `CalendarMapShareController` 53~60행 → `storage.loadThumbnail` | 썸네일 ✗ |
| 내 장소 목록·상세 | `RecordMapSidebar.vue` 66행, `RecordMapWorkspace.vue` 68·657행 (mini) | 서버 썸네일 (5·7만 ✗) |
| 라이트박스 | `TravelPhotoLightbox.vue` 115행 `contentUrl`(원본) | 원본 ✓ |

URL은 모두 `buildThumbnailUrl()`(`mediaPreview.js` 213~234행)이 `?thumbnail=true&w=폭`을 붙여 만든다. 클라이언트에서 받은 썸네일을 다시 회전하는 코드는 없다(→ 서버가 바른 방향 썸네일을 주면 이중 회전 위험 없음). 원본 `<img>`는 브라우저가 EXIF를 적용한다.

참고(방향과 무관, 비범위): `RecordMapController`·`CalendarMapShareController`는 폭 파라미터 이름이 `width`인데 `buildThumbnailUrl`은 `w`를 보낸다 → 이 두 경로는 항상 기본 폭(480)으로 응답.

---

## 3. 수정 설계

### 3.1 원칙

- 원본: 손대지 않는다(EXIF 포함 그대로). 클라이언트 원본 재인코딩 경로는 현재 없고 만들지 않는다.
- 파생물(클라이언트 썸네일·미리보기, 서버 썸네일): **보이는 방향으로 픽셀을 회전하고 EXIF를 싣지 않는다**(캔버스 `toBlob`·ImageIO 출력은 원래 EXIF가 없다 → 방향은 "Orientation 1"과 같다). 따라서 표시 측은 썸네일에 추가 회전을 하지 않는다(현재도 안 함, 유지).

### 3.2 클라이언트: 브라우저 자기 보정(calibration) 방식

"브라우저가 디코딩 때 EXIF를 적용하는가"를 추측하지 않고 **한 번 측정**한다.

새 파일 `frontend/src/lib/imageOrientation.js`(의존성 없음, node 단위 테스트 가능):
- `buildOrientationExifSegment(orientation)` → `Uint8Array`: APP1(0xFFE1) + 길이 + `"Exif\0\0"` + TIFF 리틀 엔디언 헤더(`II`, 42, IFD 오프셋 8) + IFD0 항목 1개(태그 0x0112, 형식 3=SHORT, 개수 1, 값) + 다음 IFD 0. 총 36바이트(마커 2 + 길이 2 + 6 + 26).
- `insertExifSegment(jpegBytes, segment)` → `Uint8Array`: 앞 2바이트가 `FF D8`(SOI)이 아니면 `null`. 아니면 SOI + segment + 나머지.
- `normalizeExifOrientation(value)` → 1~8 정수가 아니면 1.
- `resolveOrientationToApply(exifOrientation, browserAppliesOrientation)` → 브라우저가 적용하면 1, 아니면 `normalizeExifOrientation(exifOrientation)`.
- `isQuarterTurnOrientation(o)` → 5~8.
- `resolveOrientedSize(width, height, orientation)` → 5~8이면 `{ width: height, height: width }`, 아니면 그대로.
- `drawOrientedImage(context, image, orientation, targetWidth, targetHeight)` → 현재 `mediaPreview.js` 127~165행 `drawOrientedPreview`를 **그대로 옮긴다**(행렬은 검증 완료: 1~8 모두 올바른 변환. 바뀌는 것은 "언제 이 회전을 쓰느냐"뿐).

`frontend/src/lib/mediaPreview.js` 변경:
- `decodeImageSource(blob, objectUrl)`: ① `createImageBitmap(blob, { imageOrientation: 'from-image' })` ② 실패하면 `createImageBitmap(blob)` ③ 함수가 없거나 둘 다 실패하면 `readImageElement(objectUrl)`(objectUrl이 없으면 만들고 끝나면 revoke). 기존 `loadPreviewImage`를 이것으로 대체(`'none'` 옵션 제거).
- `detectBrowserAppliesExifOrientation()`: 모듈 수준 Promise로 1회만 계산. 2×1 캔버스(왼쪽 흰색, 오른쪽 검정) → `toBlob('image/jpeg', 0.92)` → 바이트 → `insertExifSegment(…, buildOrientationExifSegment(6))` → Blob → **같은 `decodeImageSource`로** 디코딩 → `height > width`면 `true`. 비트맵이면 `close()`. 어떤 단계든 실패하면 `true`(현행 모든 주요 엔진은 적용하며, 이중 회전이 바로 이번 버그이므로 "적용한다"가 안전한 기본값). `document`가 없으면(node) `true`.
- `prepareClientImageUpload`·`createLocalImagePreview`: `Promise.all([resolveImageOrientation(file), resolveImageGps(file)(업로드 쪽만), decodeImageSource(file, objectUrl), detectBrowserAppliesExifOrientation()])` → `orientation = resolveOrientationToApply(exif, applies)` → `resolveOrientedSize(source…, orientation)` → `renderOrientedImageBlob(image, orientation, …)`(내부에서 `drawOrientedImage` 사용). 끝나면 `image.close?.()`.
- `prepareClientImageUpload` 반환값에 `orientedThumbnails: true` 추가(썸네일을 만들었을 때만; 비이미지 분기는 `false`). 3.5절 서버 안전장치용 표식.
- `buildThumbnailUrl`: `parsed.searchParams.set('tv', THUMBNAIL_CACHE_VERSION)`(상수 `'2'`, export) 추가 → 브라우저 HTTP 캐시(하루)에 남은 옛 썸네일을 한 번에 무효화. 서버는 모르는 파라미터를 무시한다(`tv`라는 이름의 `@RequestParam`은 백엔드에 없음 — 확인함).

이중 회전 방지: 브라우저가 적용 → 그린 그대로(회전 없음), 미적용 → EXIF대로 1회 회전. 어느 쪽이든 결과는 1회 적용.

### 3.3 서버: EXIF 방향 처리 보완

`ImageThumbnailService`:
- EXIF 파서는 기존 metadata-extractor를 그대로 쓴다(`resolveExifOrientation`은 빅/리틀 엔디언 모두 정상 확인됨, 손상 시 예외를 잡아 1 반환 — 유지). 범위 밖 값(0, 9 이상)은 1로 정규화하도록 한 줄 추가.
- `applyExifOrientation`에 case 5·7 추가(AffineTransform 생성자 인자 순서는 `(m00, m10, m01, m11, m02, m12)`):
  - 5(transpose): `targetWidth = height; targetHeight = width; transform = new AffineTransform(0, 1, 1, 0, 0, 0);` → (x,y)→(y,x)
  - 7(transverse): `targetWidth = height; targetHeight = width; transform = new AffineTransform(0, -1, -1, 0, height, width);` → (x,y)→(h−y, w−x)
- 원본보다 넓은 버킷 처리: 새 오버로드 `createPreparedThumbnails(byte[] sourceBytes, String contentType, List<Integer> requestedWidths, boolean includeWiderBuckets)`. `true`면 118행 필터 대신 **렌더 폭 = min(버킷 폭, 방향 적용 후 이미지 폭)**으로 만들고 `PreparedThumbnailContent.width`는 버킷 폭으로 표기. 기존 3인자 메서드는 `false`로 위임(드라이브·가족 앨범 동작 불변). 이유: 3.4절에서 옛 여행 사진을 서버가 다시 만들게 되는데, 폭이 960보다 좁은 사진(예: 메신저로 받은 720×1280)은 detail 버킷이 영영 안 생겨 조회마다 재생성하는 루프가 생긴다.
- 드라이브 임시 캐시 키 무효화: `buildCacheKey`의 join 목록 맨 앞에 상수 `THUMBNAIL_RENDER_VERSION = "orientation-v2"` 추가.

지원 범위: 업로드 허용 형식은 JPG·PNG·WEBP·GIF·BMP(HEIC/HEIF 불가 — `TravelMediaStorageService` 1025~1064행, 내 장소 33행). EXIF Orientation은 사실상 JPEG에서만 의미가 있다. PNG/WEBP에 eXIf 청크가 있으면 서버는 metadata-extractor로 읽어 적용하지만 ImageIO에 WEBP 리더가 없어 WEBP는 서버 썸네일을 못 만든다(→ 원본으로 대체, 기존과 같음). 클라이언트는 브라우저가 `<img>`에 쓰는 것과 같은 디코딩 결과를 쓰므로 원본 표시와 일치한다. GIF·BMP는 방향 정보 없음.

### 3.4 저장된 썸네일 무효화: 썸네일 경로 버전 v2

"썸네일이 새 방향 로직으로 만들어졌는가"를 DB 없이 표시하는 방법으로 **저장 경로에 버전을 둔다.**

- 새 경로: `{원본디렉터리}/.thumbs-v2/{폭}/{원본이름}.jpg|png` (디렉터리 없는 원본이면 `.thumbs-v2/{폭}/…`). 옛 경로 `.thumbs/{폭}/…`는 "레거시"로만 읽고 지운다.
- `TravelMediaStorageService`:
  - `buildThumbnailStoragePath`는 v2 경로를 만든다. 레거시 경로용 `buildLegacyThumbnailStoragePath`(현재 로직 그대로)를 따로 둔다.
  - 새 presign 썸네일 키·서버 생성 썸네일 저장·`hasAllPreparedThumbnails`·`loadPreparedThumbnail`은 모두 v2 기준(자동으로 따라감).
  - `loadThumbnail`(336행) 재작성:
    1. v2 썸네일 있으면 반환.
    2. 이미지가 아니면 `null`(기존과 같음).
    3. 요청 시점 생성 동시성 제한: 필드 `private final Semaphore onDemandThumbnailPermits = new Semaphore(ON_DEMAND_THUMBNAIL_PERMITS);`(상수 2), `tryAcquire(ON_DEMAND_THUMBNAIL_WAIT_MS = 1500, MILLISECONDS)`. `InterruptedException`이면 인터럽트 플래그 복구 후 미획득 처리. 획득하면 `try { prepareDerivedThumbnails(...) 후 v2 다시 읽기 } finally { release }`, 있으면 반환.
    4. 미획득·생성 실패·여전히 없음 → **레거시 썸네일**(`buildLegacyThumbnailStoragePath` + `selectWidth`)을 읽어 반환(없으면 `null` → 호출부가 기존처럼 원본으로 대체). 레거시는 Orientation 1 사진이면 맞고, 회전 사진이면 잠시 틀리지만 부하 폭주를 막는다.
  - `prepareDerivedThumbnailsQuietly`(621행)는 새 4인자 오버로드를 `includeWiderBuckets = true`로 호출.
  - `deleteImageWithThumbnailsQuietly`(381행): 원본 + v2 4개 + 레거시 4개 삭제.
  - 패키지 전용 접근자 `Semaphore onDemandThumbnailPermitsForTest()`(테스트에서 허가 소진용).
- `TravelUploadGrantPaths.isThumbnail`(18~29행): v2와 레거시 **둘 다** 허용(`directory + "/.thumbs-v2/" + width + "/" …` 또는 `directory + "/.thumbs/" + width + "/" …`). 레거시를 계속 허용해야 기존 업로드 권한 행·탈퇴 인벤토리(`TravelUploadWithdrawalInventory` 77행)와 기존 테스트가 깨지지 않는다.
- `DriveService` 1114행 ETag 문자열을 `"-travel-thumb-v2\""`로 바꾼다(브라우저 304 고착 해소).
- 소급 효과:
  - 원본이 EXIF를 유지하므로 **재생성만으로 복구된다.** 화면을 열면 요청 시점 생성(동시 2개) + 백필(`TravelThumbnailBackfillService`, 기본 15분마다 100건)이 v2를 채운다. 백필 코드 변경 불필요(`ensurePreparedThumbnails`가 v2 존재 여부를 보므로 옛 사진을 전부 CREATED로 처리). 운영자가 빨리 끝내려면 `.env`의 `TRAVEL_THUMBNAIL_BACKFILL_MAX_ITEMS_PER_RUN`을 일시적으로 올린다(코드 변경 없음).
  - 내 장소 사진은 `TravelMediaAsset`이 아니라 백필 대상이 아니다 → 요청 시점 생성으로만 v2가 생기고, 그 전에는 레거시(서버 생성이라 대부분 정상)를 보여 준다.
  - **복구 불가 범위:** 업로드 전에 이미 다른 앱(메신저·편집 앱 등)이 재인코딩해 EXIF를 지운 원본은 원본 픽셀 자체가 그 방향이므로 서버도 바로잡을 수 없다(라이트박스에서도 같은 방향으로 보임 → 사용자가 구분 가능). 이번 코드는 원본을 재인코딩한 적이 없으므로 앱 때문에 원본이 망가진 사례는 없다.
  - 레거시 썸네일 객체는 미디어 삭제 시에만 함께 지워진다. 일괄 정리 배치는 비범위(7장).
- DB 스키마 변경 없음.

### 3.5 서버 안전장치: 구버전 클라이언트가 올린 썸네일 버리기

배포 후에도 새로고침하지 않은 탭(1.2절 ②)은 옛 코드로 이중 회전 썸네일을 v2 경로에 올린다. 이를 막기 위해 완료 요청에 표식을 둔다.
- 프론트: complete 본문 파일마다 `orientedThumbnails: true`(3.2절 표식이 있을 때만).
- 백엔드: `TravelMediaUploadCompleteFileRequest`에 마지막 구성요소 `Boolean orientedThumbnails` 추가 → `TravelMediaData.Completed`에 `boolean orientedThumbnails`(마지막) → `TravelMediaUploadMapper.completed`에서 `Boolean.TRUE.equals(file.orientedThumbnails())` → `TravelMediaStorageAdapter.complete`(42~49행)에서 `TravelMediaStorageService.CompletedUpload`의 새 마지막 구성요소 `boolean orientedThumbnails`로 전달.
- `completePresignedUpload`(268행): `verifyPreparedThumbnailUploads` 성공 후 `verifiedContentType`이 `image/`이고 `!upload.orientedThumbnails()`이면 `deletePreparedThumbnailsQuietly(upload.objectKey(), verifiedContentType)`(v2 4개 삭제)만 한다. 디코딩·재생성은 하지 않는다(업로드 레인에서 썸네일 작업 금지 — `docs/media_processing_queue_contract.md`). 이후 요청 시점 생성/백필이 서버 방향 로직으로 만든다.
- 배포 순서 무관: 새 프론트+옛 백엔드 → 모르는 JSON 필드는 무시(Spring Boot 기본 `FAIL_ON_UNKNOWN_PROPERTIES=false`)되고 썸네일은 이미 정확. 옛 프론트+새 백엔드 → 썸네일 폐기 후 서버 생성.
- `Completed.toString()` 마스킹은 유지.

### 3.6 표시 측 점검 결과

- 클라이언트는 받은 썸네일을 회전하지 않고, 서버 썸네일에는 EXIF가 없으므로 이중 회전 없음. 원본 `<img>`는 브라우저 EXIF 적용(`style.css`의 `image-orientation: from-image`는 기본값과 같음 — 수정 금지 파일이므로 건드리지 않음).
- 캐시: `tv=2` 파라미터로 여행 썸네일 응답의 하루 캐시를 무효화, 드라이브는 ETag 변경, 기록 지도·통합 달력은 원래 `no-store`. 서비스워커는 `/api/`를 가로채지 않는다(`sw.js` 31행).

---

## 4. 사진 추가 버튼

### 4.1 배포·캐시 대응 (코드 변경 아님 — 사용자에게 안내)

1. **배포 서버**: 배포용 체크아웃에서 `git log -1 --oneline`이 `0f6f08a4` 이상인지 확인 → `docker compose -f docker-compose.oci.app.yml up -d --build frontend backend`(또는 `build frontend` 후 `up -d frontend`). `up -d`만 하면 옛 이미지가 계속 뜬다.
2. **브라우저**: 열려 있던 탭을 새로고침(SPA). 확인: 개발자 도구 콘솔에서 `document.querySelector('.travel-memory-map-panel__actions')`가 `null`이면 구버전(이 클래스는 `0f6f08a4`에서 생김). 화면에서는 '기록 지도' 머리에 `사진 추가` 버튼, 설명이 "사진은 ‘사진 추가’로 올리세요…"이면 새 버전.
3. 그래도 옛 화면이면: 개발자 도구 → Application → Service Workers에서 `/sw.js` 등록 해제 + Storage 지우기(캐시 이름 `travelledger-shell-v2`) 후 새로고침. (정상이면 필요 없음: `index.html`·`sw.js`는 `no-store`, 내비게이션은 network-first, `/assets/`는 해시 파일명.)
4. **로컬 dev**: 저장소 루트(`main`)에서 `npm run dev`를 띄웠는지 확인. 저장소 안 `implementation-worktree/`(detached `66171318`)는 옛 UI 코드다.

### 4.2 코드 보강 (작게)

HEAD에서 버튼은 보이지만 ① 지도를 보도록 스크롤하면 머리 버튼이 화면 밖으로 사라지고 ② 다크에서 주 버튼이 강조되지 않는다. 사용자가 "해당 페이지에 사진 추가 버튼을 추가하라"고 했으므로 지도 도구 줄에 보조 버튼을 넣는다. 수정 파일은 `frontend/src/components/TravelMemoryPanel.vue` 하나(TravelMapPanel은 수정 금지 — 기존 `#toolbar` 슬롯 사용, 936~950행에 이미 `GPX 경로` 그룹이 이 방식).

1. `<template #toolbar>` 안, `GPX 경로` 그룹 **앞**에:
   ```html
   <div class="travel-map__toolbar-group travel-memory-map-toolbar-photo">
     <button
       class="travel-map__toolbar-button travel-memory-map-toolbar-photo__button"
       type="button"
       aria-label="사진 추가 (지도 도구)"
       @click="pickPhotos(null, $event)"
     >사진 추가</button>
   </div>
   ```
   접근 이름을 `사진 추가 (지도 도구)`로 달리해 `getByRole('button', { name: '사진 추가', exact: true })`가 머리 버튼 하나만 잡도록 유지(기존 e2e 계약). 핸들러는 기존 `pickPhotos`(동기 `input.click()`)를 그대로 쓴다.
2. `onPhotosPicked`(66행): `files.length`가 있으면 emit **전에** `if (document.fullscreenElement) document.exitFullscreen().catch(() => {})`. 지도 전체 화면(Fullscreen API, `TravelMapPanel` 944~960행) 상태에서 도구 줄 버튼을 누르면 가져오기 대화상자가 전체 화면 요소 뒤에 가려지기 때문.
3. 929행 `hint-text`를 `"지도를 누르면 임시 핀이 생기고, 그 핀을 다시 누르면 장소 기록을 쓸 수 있습니다. 사진은 ‘사진 추가’(패널 머리 또는 지도 도구 줄)를 쓰세요."`로.
4. 스타일(같은 파일 `<style>`의 기존 `:global(...)` 규칙들 옆, 1208행 근처):
   ```css
   /* 지도 도구 줄의 사진 추가: 배경과 싸우지 않고 테두리·inset 띠·굵기로 강조 (record_map_ux_workplan 3.6) */
   :global(html body .app-shell .record-map-hub .travel-memory-map-panel .travel-map__toolbar-button.travel-memory-map-toolbar-photo__button) {
     border-color: var(--rm-accent, #3a5a40) !important;
     box-shadow: inset 3px 0 0 var(--rm-accent, #3a5a40) !important;
     font-weight: 800 !important;
   }
   /* 다크: 머리의 주 버튼(사진 추가)을 새 기록과 구분 */
   :global(:root[data-theme='toss'] body .app-shell .record-map-hub .travel-memory-map-panel__actions .button--primary) {
     background: var(--rm-accent, #9bd3a3) !important;
     border-color: var(--rm-accent, #9bd3a3) !important;
     color: var(--rm-accent-contrast, #0f1517) !important;
     -webkit-text-fill-color: var(--rm-accent-contrast, #0f1517) !important;
   }
   ```
   적용 후 계산된 스타일을 e2e로 확인(5장 6단계). 지면 선택자 앞에 `:root` 등을 더 붙여 특이도를 올린다.
5. 진입 경로: `기록 목록·편집`, `여행 정보 수정 → 사진 · GPS`, 통합 달력 딥링크(`TravelWorkspace.openPhotoEditor` 232행 → 설정 2단계), `TravelHubWorkspace`의 세 사용처(2284행 워크플로 2단계, 2809행 `logTab==='memories'`, 2828행 앨범 업로드 탭)는 모두 같은 `TravelMemoryPanel`을 렌더하므로 위 변경이 전부에 적용된다. 추가 배선 불필요.

---

## 5. 단계별 작업 지시

공통 검증 명령(필요한 단계에서 사용):
- 프론트: `cd frontend && npm run build`, `cd frontend && npm run test:unit`
- e2e(별도 포트): 백그라운드로 `cd frontend && npx vite --host 127.0.0.1 --port 5189 --strictPort` 실행 후 `cd frontend && E2E_BASE_URL=http://127.0.0.1:5189 E2E_START_LOCAL_SERVER=0 npx playwright test <spec들> --config playwright.config.js --reporter=line`. 끝나면 vite 종료.
- 백엔드: `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests '<클래스>'` (Git Bash 기준. PowerShell이면 `$env:JAVA_HOME='C:\jdk-17'; .\gradlew.bat test --tests '<클래스>'`)
- 계약 스크립트: `powershell -ExecutionPolicy Bypass -File scripts/verify-media-processing-contract.ps1`

### 1단계 — 클라이언트 방향 판정 수정

- 목적: 브라우저 썸네일·미리보기의 이중 회전 제거.
- 파일: `frontend/src/lib/imageOrientation.js`(신규), `frontend/src/lib/mediaPreview.js`, `frontend/tests/unit/imageOrientation.test.js`(신규).
- 변경: 3.2절 그대로. `mediaPreview.js`에서 `loadPreviewImage`·`isRotatedOrientation`·`normalizeOrientation`·`drawOrientedPreview`를 제거하고 `imageOrientation.js` 함수로 대체. `exifr` import·`THUMBNAIL_*` export·`buildThumbnailUrl` 시그니처는 유지. `resolveWidthBoundedSize`·`resolvePreviewSize`는 그대로.
- 단위 테스트(node:test, `import { test } from 'node:test'`, `assert` — 기존 `tests/unit/*.test.js` 형식):
  1. `buildOrientationExifSegment(6)`: 길이 36, `[0]=0xFF,[1]=0xE1`, 길이 필드 34(빅 엔디언), 6~11바이트 `Exif\0\0`, 12~13 `II`, 태그 0x0112 리틀 엔디언, 값 6.
  2. `insertExifSegment`: SOI 아닌 입력 → `null`; 정상 입력 → 길이 합, 앞 2바이트 SOI, 그 뒤가 segment.
  3. `resolveOrientationToApply`: (6, true)→1, (6, false)→6, (0, false)→1, (9, false)→1, ('x', false)→1.
  4. `resolveOrientedSize`: 5~8 교환, 1~4 유지.
  5. `drawOrientedImage` 행렬 검증: 가짜 context(`save/restore/translate/scale/rotate/transform/drawImage`를 2D 아핀 행렬로 누적; `rotate(θ)`는 `[cos, sin, -sin, cos, 0, 0]` 곱)로 targetW=200, targetH=100(5~8은 원시 100×200을 가정해 targetW=200,targetH=100 그대로 넘기면 drawW=100,drawH=200). drawImage 시점 행렬로 원시 좌상단 (0,0)과 원시 우상단 (drawW,0)을 변환해 기대값과 비교(허용오차 1e-9):

     | o | 원시 좌상단 → | 원시 우상단 → |
     |---|---|---|
     | 1 | (0,0) | (W,0) |
     | 2 | (W,0) | (0,0) |
     | 3 | (W,H) | (0,H) |
     | 4 | (0,H) | (W,H) |
     | 5 | (0,0) | (0,H) |
     | 6 | (W,0) | (W,H) |
     | 7 | (W,H) | (W,0) |
     | 8 | (0,H) | (0,0) |
- 완료 기준: `npm run test:unit` 통과(새 테스트 포함), `npm run build` 성공, 코드에 `imageOrientation: 'none'` 문자열이 없음(`grep -rn "imageOrientation: 'none'" frontend/src` 결과 0).

### 2단계 — 업로드 표식·썸네일 URL 캐시 버전

- 목적: 서버가 새 클라이언트 썸네일을 구별(3.5절), 브라우저 캐시 무효화(3.2절).
- 파일: `frontend/src/lib/mediaPreview.js`, `frontend/src/lib/api.js`(지정 2곳만).
- 변경:
  - `mediaPreview.js`: `prepareClientImageUpload` 반환에 `orientedThumbnails`(이미지 성공 경로 `true`, 비이미지 `false`). `export const THUMBNAIL_CACHE_VERSION = '2'`, `buildThumbnailUrl`에서 `parsed.searchParams.set('tv', THUMBNAIL_CACHE_VERSION)`(`w` 다음).
  - `api.js` 1394~1400행 비이미지 기본 객체에 `orientedThumbnails: false`, 1403~1411행 `preparedEntries[index]`에 `orientedThumbnails: preparedImage.orientedThumbnails === true`.
  - `api.js` complete 본문(1512~1531행) 파일 객체에 `orientedThumbnails: preparedEntries[index]?.orientedThumbnails === true`. presign 본문은 바꾸지 않는다.
  - `git diff frontend/src/lib/api.js`로 기존 `fetchAdminWithdrawalEmbeddingStatus` 변경이 그대로 있는지 확인.
- 완료 기준: build·unit 통과. 기존 e2e `travel-map-display-modes.spec.js`(정규식 `thumbnail=true.*w=96` 등)는 그대로 통과해야 한다.
- 검증: `npx playwright test e2e/travel-map-display-modes.spec.js e2e/travel-photo-add.spec.js e2e/travel-import.spec.js …`(공통 명령).

### 3단계 — 서버 `ImageThumbnailService` 보완

- 목적: Orientation 5·7, 좁은 이미지 버킷, 드라이브 캐시 키.
- 파일: `backend/src/main/java/com/playdata/calen/common/media/ImageThumbnailService.java`, `backend/src/test/java/com/playdata/calen/common/media/ImageThumbnailServiceTest.java`.
- 변경: 3.3절. `applyExifOrientation` 시작에서 범위 밖 값 정규화는 `resolveExifOrientation` 반환부에서(`orientation == null || orientation < 1 || orientation > 8 ? 1 : orientation`).
- 테스트 추가(기존 메서드 이름 유지):
  - `shouldApplyEveryExifOrientationToPreparedThumbnails`(`@ParameterizedTest @ValueSource(ints = {1..8})`): 400×200 RGB, 좌상단 100×60 빨강, 나머지 파랑 → ImageIO JPEG → SOI 뒤에 Orientation APP1 삽입(홀수 값은 리틀, 짝수는 빅 엔디언 — 6장 빌더) → `createPreparedThumbnails(bytes, "image/jpeg", List.of(96))` → 크기 5~8은 96×192, 1~4는 96×48. 빨강 모서리(가장자리에서 2~3px 안쪽 화소, R>180 && B<120): 1 좌상, 2 우상, 3 우하, 4 좌하, 5 좌상, 6 우상, 7 우하, 8 좌하.
  - `shouldTreatOutOfRangeOrientationAsNormal`: 값 0과 9 → 96×48, 빨강 좌상.
  - `shouldKeepEveryBucketWhenWiderBucketsAreIncluded`: 800×400 PNG, 4인자 오버로드 `true`, 폭 [96,240,480,960] → 4개, 960 버킷의 실제 이미지 폭 800. 3인자 메서드는 3개(기존 동작).
- 완료 기준: `./gradlew test --tests 'com.playdata.calen.common.media.*'` 통과.

### 4단계 — 썸네일 경로 v2·레거시 폴백·동시성 제한

- 목적: 저장된 틀린 썸네일 소급 복구(3.4절).
- 파일: `backend/src/main/java/com/playdata/calen/travel/service/TravelMediaStorageService.java`, `backend/src/main/java/com/playdata/calen/travel/service/TravelUploadGrantPaths.java`, `backend/src/main/java/com/playdata/calen/drive/service/DriveService.java`(1114행 ETag 한 줄), 테스트 `backend/src/test/java/com/playdata/calen/travel/service/TravelMediaStorageServiceTest.java`, `TravelUploadGrantRegistryTest.java`(또는 신규 `TravelUploadGrantPathsTest.java`).
- 변경: 3.4절 그대로. `TravelMediaStorageServiceTest` 76행(`.thumbs` 디렉터리)과 342~343행 `thumbnailPathFor` 도우미를 `.thumbs-v2`로 갱신. 가족 앨범(`FamilyMediaStorageService`)의 `.thumbs`는 건드리지 않는다.
- 테스트 추가:
  - `loadThumbnailRegeneratesOrientedThumbnailWhenOnlyLegacyExists`(로컬 저장소 서비스, 기존 `createLocalStorageService()` 사용): Orientation 6 JPEG(raw 1600×900) `store` → v2 4개 삭제 → 레거시 경로(`.thumbs/480/…`)에 가로 더미 JPEG 기록 → `loadThumbnail(path, "image/jpeg", 480)` 결과 이미지가 세로(높이>폭)이고 v2 파일이 생겼는지.
  - `loadThumbnailFallsBackToLegacyWhenOnDemandGenerationIsBusy`: `onDemandThumbnailPermitsForTest().drainPermits()` 후 같은 준비 → 결과가 레거시 바이트와 같음, v2 미생성. 끝나면 `release(drained)`.
  - `deleteImageWithThumbnailsRemovesLegacyAndVersionedThumbnails`.
  - `ensurePreparedThumbnailsCreatesVersionedSetWhenOnlyLegacyExists`(v2 삭제·레거시만 있음 → CREATED).
  - 경로: `isThumbnail`이 `…/10/.thumbs-v2/480/photo.jpg`와 `…/10/.thumbs/480/photo.jpg` 모두 true, 다른 이름·다른 기록은 false.
- 완료 기준: `./gradlew test --tests 'com.playdata.calen.travel.*' --tests 'com.playdata.calen.drive.*' --tests 'com.playdata.calen.account.withdrawal.*' --tests 'com.playdata.calen.recordmap.*'` 통과(탈퇴·업로드 권한 테스트는 레거시 경로 문자열을 쓰므로 그대로 통과해야 한다). 계약 스크립트 통과.

### 5단계 — 완료 요청 표식 배관 + 미표식 썸네일 폐기

- 목적: 구버전 클라이언트 썸네일 차단(3.5절).
- 파일: `backend/src/main/java/com/playdata/calen/travel/dto/TravelMediaUploadCompleteFileRequest.java`, `travel/domain/model/TravelMediaData.java`(`Completed`), `travel/adapter/support/TravelMediaUploadMapper.java`, `travel/adapter/out/storage/TravelMediaStorageAdapter.java`, `travel/service/TravelMediaStorageService.java`(`CompletedUpload`, `completePresignedUpload`, 새 private `deletePreparedThumbnailsQuietly`), 그리고 생성자 호출부 테스트.
- 호출부 찾기: `cd backend && grep -rn "new Completed(\|new CompletedUpload(\|new TravelMediaUploadCompleteFileRequest(" src` — 현재 main 2곳(매퍼·어댑터), 테스트 `TravelMediaRefactoringIntegrationTest`, `TravelMediaStorageAdapterTest`, `TravelMediaUploadMapperTest`, `ManageOwnedTravelMediaServiceTest`, `TravelMediaStorageServiceTest` 등. 모든 호출부에 마지막 인자 추가(기존 테스트는 `true` 또는 `null`/`false` 중 의도에 맞게 — 썸네일 저장을 기대하는 테스트는 `true`). DTO 레코드에 보조 생성자를 만들지 않는다(Jackson 레코드 역직렬화 혼선 방지). `TravelRouteUploadMapper`의 `Completed`는 경로 파일용 다른 레코드이므로 무관한지 확인 후 손대지 않는다.
- 테스트 추가(`TravelMediaStorageServiceTest`의 기존 MinIO 목 서비스 패턴 `createPresignedStorageService` 사용):
  - `completePresignedUploadDiscardsClientThumbnailsWithoutOrientationMarker`: 표식 false → `removeObject`가 v2 썸네일 키 4개에 대해 호출됨, 원본 키에는 호출 안 됨.
  - `completePresignedUploadKeepsClientThumbnailsWithOrientationMarker`: 표식 true → `removeObject` 호출 없음.
  - `TravelMediaUploadMapperTest`: `orientedThumbnails` true/null 전달 확인.
- 완료 기준: 4단계 테스트 묶음 + `--tests 'com.playdata.calen.travel.adapter.*'` 통과. 계약 스크립트 통과.

### 6단계 — 사진 추가 버튼 보강

- 목적: 4.2절.
- 파일: `frontend/src/components/TravelMemoryPanel.vue`, `frontend/e2e/travel-photo-add.spec.js`.
- 변경: 4.2절 1~4.
- e2e 추가(`travel-photo-add.spec.js`, 기존 `pick`·`photoDialog`·`openLegacyList` 재사용):
  - `P13 the setup map toolbar has its own "사진 추가" that opens the photo dialog`(라이트·다크 둘 다 — 다크는 `page.addInitScript(() => localStorage.setItem('calen-theme-mode', 'toss'))`): `openLegacyList` → `page.getByRole('button', { name: '사진 추가 (지도 도구)' })` 보임 → 그 버튼으로 `filechooser` → 사진 1장 → `사진 추가` 대화상자 보임·저장할 여행 90. `page.getByRole('button', { name: '사진 추가', exact: true })` 개수 1(머리 버튼만).
  - P9에 다크 테마 확인 추가(또는 별도 테스트): 다크에서 머리 `사진 추가`와 `새 기록`의 계산된 `background-color`가 서로 다르고, `사진 추가`의 `-webkit-text-fill-color`가 배경과 다름.
- 완료 기준: `npx playwright test e2e/travel-photo-add.spec.js e2e/travel-import.spec.js e2e/record-map.spec.js e2e/mobile-travel-modal.spec.js`(데스크톱·모바일) 통과, build 성공. 수동: 라이트·다크·모바일에서 도구 줄 버튼이 다른 도구 버튼과 같은 높이로 정렬되고 줄바꿈이 깨지지 않는지 스크린샷(작업자 로컬 임시 폴더)으로 확인. 지도 `전체 화면` 상태에서 도구 줄 `사진 추가` → 파일 선택 → 전체 화면이 풀리고 대화상자가 보이는지.

### 7단계 — 방향 e2e·회귀·문서 한 줄

- 목적: 업로드 바이트와 표시 방향을 자동 검증.
- 파일: `frontend/e2e/fixtures/travelMock.js`(헬퍼 추가), `frontend/e2e/photo-orientation.spec.js`(신규), `docs/media_processing_queue_contract.md`("Current baseline" 표에 행 1개).
- 헬퍼: 6.1절 `orientationExifSegment`(GPS·날짜 포함판)와 `withOrientationExif(jpeg, { orientation, lat, lon, dateTime })`, 6.2절 `markerJpeg(page)`·`portraitSceneJpeg(page)`를 `travelMock.js`에 export.
- 테스트(6.3절): O1~O4.
- 계약 문서 행: `| Thumbnail orientation | Prepared thumbnails are display-oriented (EXIF applied, no EXIF in output) and stored under \`.thumbs-v2\`; legacy \`.thumbs\` variants are read only as a fallback and removed with the media. Completion without the client orientation marker discards client thumbnails so the server regenerates them. | \`TravelMediaStorageService.loadThumbnail\`, \`completePresignedUpload\`, \`ImageThumbnailService.applyExifOrientation\`. |`
- 완료 기준: 아래 전부 통과.
  - `cd frontend && npm run build && npm run test:unit`
  - e2e: `photo-orientation.spec.js travel-photo-add.spec.js travel-import.spec.js travel-map-display-modes.spec.js travel-map-pin.spec.js record-map.spec.js mobile-travel-modal.spec.js`
  - 백엔드: `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test`(전체. 시간이 오래 걸리면 최소 4·5단계 묶음 + `common.media.*`)
  - 계약 스크립트
  - `git status`로 이 작업 파일만 바뀌었는지 확인(다른 미커밋 변경은 그대로).

---

## 6. 테스트 계획

### 6.1 테스트 이미지 만들기 (저장소에 파일을 두지 않는다)

Orientation만 넣는 APP1(서버 단위 테스트·간단 e2e용). TIFF 26바이트: 헤더 8 + 항목 수 2 + 항목 12 + 다음 IFD 4.

```js
// Node(Buffer). littleEndian=false면 'MM' 빅 엔디언
function orientationOnlyExif(orientation, littleEndian = true) {
  const t = Buffer.alloc(26)
  const u16 = (v, at) => littleEndian ? t.writeUInt16LE(v, at) : t.writeUInt16BE(v, at)
  const u32 = (v, at) => littleEndian ? t.writeUInt32LE(v, at) : t.writeUInt32BE(v, at)
  t.write(littleEndian ? 'II' : 'MM', 0, 'ascii'); u16(42, 2); u32(8, 4)
  u16(1, 8); u16(0x0112, 10); u16(3, 12); u32(1, 14); u16(orientation, 18); u32(0, 22)
  const payload = Buffer.concat([Buffer.from('Exif\0\0', 'binary'), t])
  const head = Buffer.alloc(4); head.writeUInt16BE(0xFFE1, 0); head.writeUInt16BE(payload.length + 2, 2)
  return Buffer.concat([head, payload])
}
const withSegment = (jpeg, seg) => Buffer.concat([jpeg.subarray(0, 2), seg, jpeg.subarray(2)])
```

Orientation + 날짜 + GPS(가져오기 대화상자가 제목·날짜를 채우게 할 때). 기존 `exifSegment`(travelMock.js 6~22행)에서 IFD0 항목을 3개로 늘리고 오프셋을 12씩 민 판(총 190바이트, 검증 완료):

```js
export function orientationExifSegment({ orientation, lat, lon, dateTime }) {
  const t = Buffer.alloc(190)
  const entry = (at, tag, type, count, value) => { t.writeUInt16LE(tag, at); t.writeUInt16LE(type, at + 2); t.writeUInt32LE(count, at + 4); t.writeUInt32LE(value, at + 8) }
  const ref = (at, tag, text) => { t.writeUInt16LE(tag, at); t.writeUInt16LE(2, at + 2); t.writeUInt32LE(2, at + 4); t.write(text + '\0', at + 8, 'ascii') }
  const dms = (at, v) => { const d = Math.floor(v), mf = (v - d) * 60, m = Math.floor(mf), s = Math.round((mf - m) * 60000)
    ;[[d, 1], [m, 1], [s, 1000]].forEach(([n, den], i) => { t.writeUInt32LE(n, at + i * 8); t.writeUInt32LE(den, at + i * 8 + 4) }) }
  t.write('II', 0, 'ascii'); t.writeUInt16LE(42, 2); t.writeUInt32LE(8, 4)
  t.writeUInt16LE(3, 8)
  t.writeUInt16LE(0x0112, 10); t.writeUInt16LE(3, 12); t.writeUInt32LE(1, 14); t.writeUInt16LE(orientation, 18)
  entry(22, 0x8769, 4, 1, 50); entry(34, 0x8825, 4, 1, 88); t.writeUInt32LE(0, 46)
  t.writeUInt16LE(1, 50); entry(52, 0x9003, 2, 20, 68); t.writeUInt32LE(0, 64); t.write(dateTime + '\0', 68, 'ascii')
  t.writeUInt16LE(4, 88); ref(90, 0x0001, 'N'); entry(102, 0x0002, 5, 3, 142); ref(114, 0x0003, 'E'); entry(126, 0x0004, 5, 3, 166); t.writeUInt32LE(0, 138)
  dms(142, lat); dms(166, lon)
  const payload = Buffer.concat([Buffer.from('Exif\0\0', 'binary'), t])
  const head = Buffer.alloc(4); head.writeUInt16BE(0xFFE1, 0); head.writeUInt16BE(payload.length + 2, 2)
  return Buffer.concat([head, payload])
}
export const withOrientationExif = (jpeg, meta) => Buffer.concat([jpeg.subarray(0, 2), orientationExifSegment(meta), jpeg.subarray(2)])
```

Java(서버 테스트)는 같은 바이트 배치를 `ByteBuffer.order(LITTLE_ENDIAN|BIG_ENDIAN)`로 만든다: `II|MM`, short 42, int 8, short 1, short 0x0112, short 3, int 1, short 값, short 0, int 0 → 앞에 `FF E1`, 길이(=6+26+2, 빅 엔디언), `"Exif\0\0"`.

### 6.2 그림

- `markerJpeg(page)`: 캔버스 120×60(서버는 400×200), 전체 파랑 `#3366cc`, 좌상단 30×20 빨강 `#ff0000`, `toDataURL('image/jpeg', 0.9)`. 모서리 판정: 각 모서리에서 2~3px 안쪽 화소가 R>180 && B<120이면 빨강.
- `portraitSceneJpeg(page)`: 캔버스 480×240에 `translate(0, 240); rotate(-π/2)` 후 240×480 좌표계로 "위=하늘색 `#7cc4ff`(0~300), 아래=녹색 `#3a9d4a`(300~480)" 장면을 그림 → 원시 픽셀은 가로, Orientation 6을 붙이면 바른 모습은 세로(위 하늘).
- 기대 표(원시 좌상단 표식의 표시 위치, 크기): 1 좌상·가로, 2 우상·가로, 3 우하·가로, 4 좌하·가로, 5 좌상·세로, 6 우상·세로, 7 우하·세로, 8 좌하·세로.

### 6.3 e2e `frontend/e2e/photo-orientation.spec.js`

공통: `createState()` + `mock(page, state)` 후 `page.route('**/__mock-upload/**', …)`를 **mock 뒤에** 다시 등록해 `route.request().postDataBuffer()`와 경로를 배열에 모으고 `fulfill({status:200})`(나중에 등록한 핸들러가 먼저 실행됨). 끝에 `expect(state.errors).toEqual([])`.

- **O1 세로 사진(6)의 업로드 바이트**: `openMap(page,{plan:90})` → `portraitSceneJpeg` + `withOrientationExif({orientation:6, lat:35.0116, lon:135.7681, dateTime:'2026:10:03 15:40:00'})` → 머리 `사진 추가`로 선택 → 대화상자 미리보기 `li.travel-import-item img`의 `naturalHeight > naturalWidth` → 저장. 검증: 원본 PUT(`…/501-0`) 본문이 입력 Buffer와 `Buffer.compare === 0`; 썸네일 PUT 4개를 `page.evaluate`에서 `createImageBitmap(new Blob([bytes]))`로 디코딩 → pin 96×192, mini 240×480, preview·detail 240×480(원본보다 크게 만들지 않음); 캔버스로 (폭/2, 5) 화소가 하늘색 계열(B>200, G>150), (폭/2, 높이−5)가 녹색 계열(G>120, R<100); complete 요청 본문 `files[0].orientedThumbnails === true`.
- **O2 각 방향의 썸네일**(데스크톱만, `test.skip(project !== 'chromium-desktop')`): 1~8 각각 `markerJpeg` + `orientationOnlyExif`(GPS 없음 — 대화상자 저장이 위치를 요구하면 `page.evaluate`에서 `import('/src/lib/mediaPreview.js')`로 `prepareClientImageUpload`를 직접 호출해도 된다. 이때 dev 서버에서는 경로 `/src/lib/mediaPreview.js`로 동적 import 가능) → mini 썸네일의 크기·빨강 모서리가 6.2절 표와 일치.
- **O3 Orientation 1은 그대로**: O2에 포함(크기 120×60, 좌상).
- **O4 썸네일 URL 캐시 버전**: 지도 사진 핀이 있는 상태(`seedPhotoRecord`)에서 `img[src*="thumbnail=true"]`의 src에 `tv=2` 포함.

### 6.4 백엔드 단위 테스트

3단계(ImageThumbnailServiceTest 3개), 4단계(TravelMediaStorageServiceTest 4개 + 경로 1개), 5단계(완료 2개 + 매퍼 1개). 이미지 생성은 기존 테스트의 `createJpegBytes`/`createPngBytes` 패턴을 따르고 Orientation APP1은 6.1절 Java 방식으로 삽입.

### 6.5 표시 검증(수동, 스크린샷은 작업자 로컬 임시 폴더)

배포 전 로컬(백엔드+MinIO가 있는 환경)에서: ① 세로로 찍은 테스트 JPEG(직접 만든 것)를 '사진 추가'로 올림 → 지도 핀·인스펙터·사진첩·기록 목록 썸네일·라이트박스가 모두 세로. ② 이 작업 전에 올린 세로 사진(있다면)이 첫 조회 후 세로로 바뀜(서버 로그에 썸네일 생성, 브라우저 Network에서 `tv=2` URL 200). ③ 통합 달력 지도 사진·공유 링크 화면도 세로. ④ 다크·모바일에서 '사진 추가' 두 버튼 확인.

---

## 7. 리스크와 비범위

### 리스크

| 리스크 | 대응 |
|---|---|
| v2 전환 직후 옛 사진 조회가 몰리면 서버가 원본을 디코딩(사진당 수 MB, 최대 4천만 화소) | 요청 시점 생성 동시 2개 + 1.5초 대기 후 레거시 폴백, 백필은 기존 상한 유지. 메모리 여유가 적으면 `ON_DEMAND_THUMBNAIL_PERMITS`를 1로 |
| 레거시 폴백 동안 회전 사진은 잠시 계속 옆으로 보임 | 백필이 순차로 채움. 운영자가 백필 상한을 일시 상향 가능(4.1·3.4절) |
| 브라우저 보정 탐침이 실패하는 특수 환경 | 기본값 "적용함" → 최악이어도 회전 누락(이중 회전보다 덜 나쁨), 그리고 서버 썸네일과 무관한 미리보기만 영향. 표식은 그대로 true라 썸네일이 틀리면 남음 — 이 경우는 7장 비범위의 수동 재생성으로 처리 |
| `Completed`·`CompletedUpload` 구성요소 추가로 테스트 컴파일 오류 | 5단계 grep 목록의 모든 호출부를 갱신 |
| `.thumbs-v2` 경로를 다른 코드가 모름 | `.thumbs` 문자열 사용처는 `TravelMediaStorageService`, `TravelUploadGrantPaths`, `FamilyMediaStorageService`(별개 저장소)뿐임을 확인함. 탈퇴 인벤토리는 `isThumbnail`을 통해 둘 다 허용 |
| 썸네일 URL에 `tv` 추가로 모든 썸네일 브라우저 캐시가 1회 무효화 | 의도된 1회 비용 |

### 비범위 (이유)

- **레거시 `.thumbs` 객체 일괄 삭제·전체 재처리 배치**: 대량 객체 삭제는 되돌릴 수 없고 탈퇴·백업 정책과 얽힌다. 미디어 삭제 시 함께 지우는 것으로 충분.
- **Orientation 1 사진의 레거시 썸네일을 서버 측 복사로 v2에 옮기는 최적화**: 원본 헤더 범위 읽기·객체 복사가 필요해 구현 범위가 커진다. 재생성으로도 결과는 같다.
- **가족 앨범 저장 썸네일의 5·7 재생성**: 공통 서비스 수정으로 새 업로드는 고쳐지지만 기존 `.thumbs`는 그대로(5·7은 매우 드묾).
- **EXIF가 이미 지워진 원본의 방향 복구**: 정보가 없어 불가능. 원본 회전 편집 기능은 별도 기능.
- **원본 회전 버튼(사용자가 수동으로 90° 돌리기)**, HEIC 지원, WEBP 서버 썸네일.
- **`RecordMapController`·`CalendarMapShareController`의 `width` vs `w` 파라미터 불일치**(항상 480 응답): 방향과 무관, 별도 수정.
- 빌드 버전 표시(예: 화면에 커밋 해시 노출): 구버전 판별에 유용하지만 Docker 빌드에 `.git`이 없어 별도 설계 필요.
