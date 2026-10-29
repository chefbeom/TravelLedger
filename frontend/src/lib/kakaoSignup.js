const callbackMessages = Object.freeze({
  cancelled: '카카오 인증을 취소했습니다. 원하면 다시 시도할 수 있습니다.',
  'invalid-state': '카카오 인증 요청이 만료되었거나 일치하지 않습니다. 처음부터 다시 시도해 주세요.',
  'registration-closed': '현재 신규 회원가입이 닫혀 있습니다. 이미 연결한 계정은 카카오로 로그인할 수 있습니다.',
  'existing-account': '같은 이메일로 가입한 계정이 있습니다. 기존 로그인 ID로 로그인해 주세요. 계정을 자동으로 연결하지는 않습니다.',
  'email-required': '카카오 계정의 인증된 이메일 제공이 필요합니다. 이메일 인증과 동의 여부를 확인해 주세요.',
  unavailable: '카카오 로그인에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  failed: '카카오 인증을 완료하지 못했습니다. 다시 시도해 주세요.',
})

// Never render arbitrary provider error descriptions or query-string contents.
export function kakaoCallbackMessage(code) {
  return code ? (Object.hasOwn(callbackMessages, code) ? callbackMessages[code] : callbackMessages.failed) : ''
}

export function validateKakaoSignup(form) {
  if (!form.loginId?.trim() || form.loginId.trim().length > 60) return '로그인 ID를 60자 이내로 입력해 주세요.'
  if (!form.displayName?.trim() || form.displayName.trim().length > 80) return '표시 이름을 80자 이내로 입력해 주세요.'
  if ((form.password?.trim().length || 0) < 8) return '비밀번호는 8자 이상이어야 합니다.'
  if (form.password !== form.passwordConfirmation) return '비밀번호 확인이 일치하지 않습니다.'
  if (!/^\d{8}$/.test(form.secondaryPin || '')) return '2차 비밀번호 숫자 8자리를 모두 입력해 주세요.'
  return ''
}
