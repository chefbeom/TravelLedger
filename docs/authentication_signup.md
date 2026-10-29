# 회원가입 및 카카오 로그인 운영 계약

## 가입 흐름

- 초대 링크 가입(`/api/invites/accept`)은 기존처럼 이메일 인증 없이 계정을 만들고 로그인할 수 있습니다.
- 관리자가 공개 회원가입을 켠 경우 `/api/auth/register`는 `202 Accepted`와 함께 인증 메일을 발송합니다.
- 일반 가입 계정은 이메일 인증 전까지 `active=false`로 유지되며 로그인할 수 없습니다.
- 인증 링크는 프런트엔드의 `#/verify-email/{token}`으로 열리고, 프런트엔드가 `/api/auth/email-verification/verify`를 호출하면 계정이 활성화됩니다.
- 인증 메일 재전송은 `/api/auth/email-verification/resend`이며 동일 계정에 대해 60초 쿨다운이 적용됩니다.

인증 토큰 원문은 DB에 저장하지 않고 SHA-256 해시만 `email_verification_tokens`에 저장합니다. 메일 발송 실패 시 가입 트랜잭션도 성공으로 남지 않습니다.

## 카카오 로그인 흐름

- `GET /api/auth/oauth/kakao/start`가 일회성 OAuth state를 세션에 저장한 뒤 카카오로 이동합니다.
- 콜백은 state와 5분 유효시간을 검증하고 카카오의 인증된 이메일을 확인합니다.
- 이미 연결된 소셜 계정은 바로 로그인합니다.
- 새 계정은 공개 가입 정책이 켜져 있을 때만 `#/oauth/kakao/complete`로 이동해 TravelLedger 로그인 ID·비밀번호·2차 비밀번호를 입력합니다.
- 가입 화면은 `GET /api/auth/oauth/kakao/registration`으로 세션의 인증된 이메일·닉네임·만료 시각을 조회합니다. 닉네임은 표시 이름의 초기값으로 사용하고 사용자가 수정할 수 있습니다. 이메일·소셜 식별자는 가입 폼에서 받지 않습니다.
- 가입 대기는 인증 후 10분 동안 유효합니다. 만료·취소된 세션으로는 가입할 수 없고, 완료 후에는 같은 대기 세션을 재사용할 수 없습니다. 입력 오류나 중복 ID는 수정 후 다시 제출할 수 있습니다.
- `POST /api/auth/oauth/kakao/cancel`은 카카오 가입 대기와 OAuth 상태만 지웁니다. 새로운 카카오 인증을 시작할 때도 이전 대기를 제거합니다. 가입 완료·취소 POST는 기존 CSRF 보호를 유지합니다.
- 인증 취소, state 불일치/만료, 이메일 인증·동의 부족, 기존 이메일 계정, 공개 가입 마감, 제공자 통신 실패를 고정 오류 코드로 구분합니다. 카카오 응답 원문이나 토큰을 URL·화면에 노출하지 않습니다.
- 신규 가입을 닫아도 이미 연결된 활성 계정의 카카오 로그인은 유지합니다. 비활성 계정은 로그인할 수 없습니다.
- 기존 이메일 계정과 카카오 계정을 자동 병합하지 않습니다. 계정 연결 기능은 별도 명시적 인증 흐름으로 추가해야 합니다.

이 흐름은 **카카오 인증 + 서비스 계정 정보 설정**이며 비밀번호 없는 간편가입은 아닙니다. 기존 일반 로그인 및 개인정보 보호 기능에 사용하는 ID·비밀번호·숫자 8자리 2차 비밀번호를 유지합니다. 카카오 인증 이메일에는 별도 인증 메일을 발송하지 않습니다.

카카오 개발자 콘솔의 Redirect URI는 다음 백엔드 주소로 등록합니다.

```text
https://<공개 앱 주소>/api/auth/oauth/kakao/callback
```

## 운영 설정

실제 비밀번호·클라이언트 시크릿은 저장소에 넣지 않고 운영 secret/env 관리 수단으로 주입합니다.

- 이메일: `APP_AUTH_EMAIL_VERIFICATION_ENABLED`, `APP_AUTH_EMAIL_VERIFICATION_BASE_URL`, `APP_AUTH_EMAIL_VERIFICATION_TOKEN_VALIDITY`, `APP_AUTH_EMAIL_VERIFICATION_FROM`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`
- 카카오: `APP_AUTH_KAKAO_ENABLED`, `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `KAKAO_REDIRECT_URI`, `KAKAO_FRONTEND_BASE_URL`
- 필요하면 `KAKAO_AUTHORIZATION_URI`, `KAKAO_TOKEN_URI`, `KAKAO_USER_INFO_URI`와 timeout 값을 조정합니다.

배포 전에는 SMTP 인증 메일이 실제로 발송되는지, 카카오 콘솔 Redirect URI와 `KAKAO_REDIRECT_URI`가 정확히 일치하는지, 앱과 메일 링크가 HTTPS를 사용하는지 확인합니다.

## 키 준비 후 활성화 절차

현재 소스와 배포 예시의 `APP_AUTH_KAKAO_ENABLED` 기본값은 `false`로 유지합니다. 이 구현 작업에서 운영 공개 가입이나 카카오 실연동을 켜지 않습니다.

1. 카카오 개발자 앱의 로그인 사용 설정과 REST API 키를 준비하고, 이메일(`account_email`)·닉네임(`profile_nickname`) 제공 동의항목을 확인합니다. 이메일을 받았더라도 `is_email_valid`와 `is_email_verified`가 참인 경우에만 신규 가입에 사용합니다. [카카오 REST API 문서](https://developers.kakao.com/docs/ko/kakaologin/rest-api)
2. 등록한 Redirect URI를 `KAKAO_REDIRECT_URI`에 정확히 동일하게 지정합니다. `KAKAO_FRONTEND_BASE_URL`은 해시 경로 없는 공개 프런트엔드 주소입니다. REST API 키는 `KAKAO_CLIENT_ID`에, Client Secret은 카카오 앱의 활성화 설정에 맞춰 `KAKAO_CLIENT_SECRET`에 서버 환경변수로 주입합니다. 키를 저장소·채팅·프런트엔드 코드에 넣지 않습니다.
3. 프런트엔드와 백엔드를 함께 배포하고 기존 `V20260831_021__account_email_verification_social_login.sql` 적용 여부를 확인합니다. 이번 보완에 추가 DB 스키마 마이그레이션은 없습니다. 배포 중이던 구형 가입 대기 세션은 다시 카카오 인증을 받아야 합니다.
4. 키 설정 후 `APP_AUTH_KAKAO_ENABLED=true`로 전환하고 `GET /api/auth/registration-options`의 `socialLoginProviders`에 `KAKAO`가 표시되는지 확인합니다. 이는 설정 감지일 뿐 실제 카카오 인증 성공을 보증하지 않습니다. 관리자 가입 정책 화면에서도 설정 감지/누락 상태를 표시하며 키 값은 표시하지 않습니다.
5. 관리자의 공개 회원가입 ON/OFF는 기존 정책을 사용합니다. 공개 전 실제 카카오 신규 가입·재로그인·인증 취소·중복 이메일·만료·PC/모바일 세션 유지까지 별도 검증합니다. 공개 가입을 켜면 기존 이메일 가입 경로도 함께 열리므로 이메일 가입을 제공할 경우 SMTP 실발송 검증도 필요합니다.

OAuth state는 서버 세션에 보관하고 일회성·5분 만료를 검증합니다. 이는 카카오가 안내하는 [state 기반 CSRF 방어 권장 사항](https://developers.kakao.com/docs/ko/getting-started/security-guideline)을 따릅니다. 가입 조회와 인증 리다이렉트 응답에는 `Cache-Control: no-store`를 지정합니다.

## 검증 및 범위

- `KakaoRegistrationIntegrationTest`: 격리 H2에서 신규 계정·기본 분류 생성, 서버 인증 정보 우선, 권한 상승 방지, 재로그인, 비활성 계정 차단, 공개 가입 정책, 이메일 중복, 세션 만료/취소/재사용, CSRF 누락 차단, 오류 후 재시도를 확인합니다.
- `KakaoOAuthClientTest`: 모의 카카오 서버로 인가 URL·토큰 교환·이메일 인증 검사·닉네임 매핑·잘못된 식별자 거절을 확인합니다.
- `PublicRegistrationIntegrationTest`, `ProfileCredentialIntegrationTest`: 기존 이메일/초대 가입, 일반 로그인, 비밀번호/2차 비밀번호 기능을 회귀 검증합니다.
- `frontend/e2e/kakao-registration.spec.js`: 모의 인증 페이지와 API를 사용하여 가입→콜백→추가 정보→완료, 실패·재시도·취소, 관리자 표시, 1440/1024/390/320px 및 밝은/어두운 테마를 확인합니다.
- 키가 아직 준비되지 않아 실제 카카오 API 호출, 카카오 개발자 앱 설정, VM/운영 배포는 수행하지 않습니다. 구글/네이버, 기존 계정 연결·해제, 비밀번호 없는 가입은 이번 범위에 포함하지 않습니다.

### 로컬 검증 결과

- 백엔드 총 29건 통과: 카카오 가입 통합 14, 카카오 클라이언트 5, 기존 공개 가입 1, 기존 로그인/프로필 자격 증명 9.
- 브라우저 총 40건 통과: 카카오 가입 18, 기존 가계부 WYSIWYG 20, 관리자 이미지 동시성 설정 2. PC·모바일과 밝은/어두운 테마를 포함합니다.
- 프런트 단위 테스트 71건 및 프로덕션 빌드 통과.
- PWA/모바일 기준·DB 마이그레이션 규칙·작업 파일 UTF-8 BOM 검사·`git diff --check` 통과.
- 모의 API/격리 H2 결과이며 실제 카카오 인증·운영 공개 가입 검증을 대신하지 않습니다. 키 준비 후 위 활성화 절차의 실연동 검증이 남아 있습니다.
