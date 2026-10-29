<script setup>
import { onMounted, reactive, ref } from 'vue'
import PinPadInput from './PinPadInput.vue'
import { fetchKakaoRegistrationSession, cancelKakaoRegistration } from '../lib/sessionApi'
import { kakaoCallbackMessage, validateKakaoSignup } from '../lib/kakaoSignup'

defineProps({
  submitting: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['complete', 'go-login', 'restart'])
const form = reactive({
  loginId: '',
  displayName: '',
  password: '',
  passwordConfirmation: '',
  secondaryPin: '',
  rememberDevice: true,
})
const validationMessage = ref('')
const session = ref(null)
const loading = ref(true)
const leaving = ref(false)
const sessionMessage = ref('')

onMounted(async () => {
  try {
    const verified = await fetchKakaoRegistrationSession()
    if (!verified?.email) throw new Error('카카오 인증 정보를 확인할 수 없습니다. 다시 인증해 주세요.')
    session.value = verified
    form.displayName = String(verified.displayName || '').trim().slice(0, 80)
  } catch (error) {
    sessionMessage.value = error.status === 403 ? kakaoCallbackMessage('registration-closed') : error.message
  } finally {
    loading.value = false
  }
})

function submit() {
  if (!session.value || leaving.value) return
  validationMessage.value = validateKakaoSignup(form)
  if (validationMessage.value) return
  // Identity is taken from the verified server session, never from form fields.
  emit('complete', {
    loginId: form.loginId.trim(),
    displayName: form.displayName.trim(),
    password: form.password,
    secondaryPin: form.secondaryPin,
    rememberDevice: form.rememberDevice,
  })
}

async function cancel() {
  leaving.value = true
  validationMessage.value = ''
  try {
    await cancelKakaoRegistration()
    emit('go-login')
  } catch (error) {
    validationMessage.value = error.message
  } finally {
    leaving.value = false
  }
}
</script>

<template>
  <section class="auth-shell kakao-registration" :aria-busy="loading">
    <div class="auth-copy">
      <span class="auth-copy__badge">카카오 회원가입</span>
      <h1>카카오 인증 후 가입을 마무리하세요.</h1>
      <p>인증된 이메일과 닉네임을 확인하고 TravelLedger에서 사용할 보안 정보를 정해 주세요. 별도 인증 메일은 보내지 않습니다.</p>
      <p class="auth-copy__hint">기존 계정과 자동 병합하지 않습니다. 서비스용 비밀번호와 2차 비밀번호는 기존 로그인·개인정보 보호 기능에 사용됩니다.</p>
    </div>

    <article class="auth-card">
      <h2>계정 정보 입력</h2>
      <p v-if="loading" role="status">카카오 인증 정보를 확인하는 중입니다...</p>
      <template v-else-if="session">
        <dl class="kakao-verified-profile" aria-label="인증된 카카오 정보">
          <div><dt>인증된 이메일</dt><dd>{{ session.email }}</dd></div>
          <div><dt>카카오 닉네임</dt><dd>{{ session.displayName || '닉네임 없음' }}</dd></div>
        </dl>
        <p class="form-hint">카카오 인증 후 10분 이내에 가입을 완료해 주세요. 아래 정보는 서비스에서 사용할 계정 정보입니다.</p>
        <form class="stack-form" @submit.prevent="submit">
          <label class="field"><span class="field__label">로그인 ID</span><input v-model="form.loginId" type="text" autocomplete="username" maxlength="60" :disabled="submitting || leaving" required /></label>
          <label class="field"><span class="field__label">표시 이름</span><input v-model="form.displayName" type="text" autocomplete="name" maxlength="80" :disabled="submitting || leaving" required /></label>
          <label class="field"><span class="field__label">비밀번호</span><input v-model="form.password" type="password" autocomplete="new-password" minlength="8" :disabled="submitting || leaving" required /></label>
          <label class="field"><span class="field__label">비밀번호 확인</span><input v-model="form.passwordConfirmation" type="password" autocomplete="new-password" minlength="8" :disabled="submitting || leaving" required /></label>
          <PinPadInput
            v-model="form.secondaryPin"
            label="2차 비밀번호"
            hint="개인정보 보호와 일반 로그인에 사용할 숫자 8자리를 설정해 주세요."
            :disabled="submitting || leaving"
          />
          <label class="checkbox-row">
            <input v-model="form.rememberDevice" type="checkbox" :disabled="submitting || leaving" />
            <span>이 기기에서 로그인 상태 유지</span>
          </label>
          <p v-if="validationMessage" class="feedback feedback--error" role="alert">{{ validationMessage }}</p>
          <button class="button button--primary" type="submit" :disabled="submitting || leaving">
            {{ submitting ? '가입 처리 중...' : '카카오 계정으로 가입 완료' }}
          </button>
        </form>
      </template>
      <p v-else class="feedback feedback--error" role="alert">{{ sessionMessage || '카카오 인증 정보를 확인할 수 없습니다.' }}</p>
      <div v-if="!loading" class="stack-form kakao-registration__actions">
        <button class="button button--ghost button--kakao" type="button" :disabled="submitting || leaving" @click="emit('restart')">카카오로 다시 인증</button>
        <button class="button button--ghost" type="button" :disabled="submitting || leaving" @click="cancel">{{ leaving ? '가입 취소 중...' : '가입 취소하고 로그인으로' }}</button>
        <p v-if="!session && validationMessage" class="feedback feedback--error" role="alert">{{ validationMessage }}</p>
      </div>
    </article>
  </section>
</template>

<style scoped>
.kakao-registration { align-items: start; }
.kakao-registration .auth-copy { align-content: start; }
.kakao-registration :is(.auth-copy, .auth-card) { min-width: 0; }
.kakao-verified-profile { margin: 16px 0; padding: 14px; border: 1px solid var(--line); background: var(--surface-soft); }
.kakao-verified-profile > div { display: grid; gap: 4px; margin-bottom: 12px; }
.kakao-verified-profile > div:last-child { margin-bottom: 0; }
.kakao-verified-profile dt { font-size: 12px; color: var(--text-soft); }
.kakao-verified-profile dd { margin: 0; overflow-wrap: anywhere; }
.kakao-registration__actions { margin-top: 16px; }
</style>
