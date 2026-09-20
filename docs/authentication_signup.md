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
- 기존 이메일 계정과 카카오 계정을 자동 병합하지 않습니다. 계정 연결 기능은 별도 명시적 인증 흐름으로 추가해야 합니다.

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
