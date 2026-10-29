import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { kakaoCallbackMessage, validateKakaoSignup } from '../../src/lib/kakaoSignup.js'

const valid = { loginId: ' new-user ', displayName: ' 카카오 사용자 ', password: 'password8', passwordConfirmation: 'password8', secondaryPin: '12345678' }

test('Kakao callback issues are fixed messages and never reflect arbitrary provider input', () => {
  assert.equal(kakaoCallbackMessage(''), '')
  assert.match(kakaoCallbackMessage('existing-account'), /자동으로 연결하지/)
  for (const code of ['cancelled', 'invalid-state', 'registration-closed', 'email-required', 'unavailable', 'failed']) assert.ok(kakaoCallbackMessage(code))
  for (const code of ['<script>secret</script>', '__proto__', 'toString', 'unknown']) assert.equal(kakaoCallbackMessage(code), kakaoCallbackMessage('failed'))
})

test('Kakao onboarding validates the existing ID, password and PIN policy without changing form data', () => {
  const original = structuredClone(valid)
  assert.equal(validateKakaoSignup(valid), '')
  for (const [field, value] of [['loginId', ' '], ['loginId', 'a'.repeat(61)], ['displayName', ''], ['displayName', '가'.repeat(81)], ['password', '        '], ['passwordConfirmation', 'different'], ['secondaryPin', '1234'], ['secondaryPin', 'abcdefgh']]) {
    assert.ok(validateKakaoSignup({ ...valid, [field]: value }))
  }
  assert.deepEqual(valid, original)
})
