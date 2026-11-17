<script setup>
import { ref, watch } from 'vue'
import { verifyEmail, verifyProfileEmail } from '../lib/sessionApi'

const props = defineProps({
  token: {
    type: String,
    default: '',
  },
  profileMode: { type: Boolean, default: false },
})

const emit = defineEmits(['go-login', 'go-signup', 'verified'])
const status = ref('ready')
const message = ref('직접 요청한 이메일 인증이라면 아래 버튼을 눌러 완료해 주세요.')
const secondaryPin = ref('')
let verificationAttempt = 0

async function verify() {
  if (!props.token) {
    status.value = 'error'
    message.value = '이메일 인증 링크가 없습니다.'
    return
  }
  if (!props.profileMode && !/^[0-9]{8}$/.test(secondaryPin.value)) {
    status.value = 'error'
    message.value = '가입 시 설정한 숫자 8자리 2차 비밀번호를 입력해 주세요.'
    return
  }
  const attempt = ++verificationAttempt
  const profileMode = props.profileMode
  const payload = profileMode ? { token: props.token } : { token: props.token, secondaryPin: secondaryPin.value }
  status.value = 'loading'
  message.value = '이메일 인증 링크를 확인하는 중입니다...'
  try {
    const response = await (profileMode ? verifyProfileEmail : verifyEmail)(payload)
    if (attempt !== verificationAttempt) return
    status.value = response?.verified ? 'success' : 'error'
    message.value = response?.message || '이메일 인증 결과를 확인할 수 없습니다.'
    if (response?.verified) secondaryPin.value = ''
    if (response?.verified && profileMode) emit('verified')
  } catch (error) {
    if (attempt !== verificationAttempt) return
    status.value = 'error'
    message.value = error.message
  }
}

// Merely opening the link must not complete verification: mail scanners may open it automatically.
watch(() => [props.token, props.profileMode], () => {
  verificationAttempt++
  secondaryPin.value = ''
  status.value = props.token ? 'ready' : 'error'
  message.value = props.token
    ? '직접 요청한 이메일 인증이라면 아래 버튼을 눌러 완료해 주세요.'
    : '이메일 인증 링크가 없습니다.'
}, { immediate: true })
</script>

<template>
  <section class="auth-shell">
    <div class="auth-copy">
      <span class="auth-copy__badge">이메일 인증</span>
      <h1>{{ profileMode ? '계정 이메일을 인증하세요.' : '가입을 마무리하세요.' }}</h1>
      <p>이메일 인증 링크는 한 번만 사용할 수 있고 설정된 시간 동안만 유효합니다.</p>
      <p>직접 요청하지 않았다면 인증하지 말고 이 창을 닫아 주세요.</p>
    </div>

    <article class="auth-card">
      <h2>{{ status === 'loading' ? '인증 확인 중' : status === 'success' ? '인증 완료' : status === 'ready' ? '인증 확인' : '인증할 수 없습니다' }}</h2>
      <p :class="status === 'success' ? 'feedback feedback--success' : status === 'error' ? 'feedback feedback--error' : ''" role="status">
        {{ message }}
      </p>
      <form v-if="token && (status === 'ready' || status === 'error')" class="stack-form" @submit.prevent="verify">
        <label v-if="!profileMode" for="signup-verification-pin">가입 시 설정한 2차 비밀번호</label>
        <input v-if="!profileMode" id="signup-verification-pin" v-model.trim="secondaryPin" type="password"
          inputmode="numeric" autocomplete="off" minlength="8" maxlength="8" pattern="[0-9]{8}" required />
        <button class="button button--primary" type="submit">이메일 인증하기</button>
      </form>
      <div class="panel__actions">
        <button v-if="status === 'error' && !profileMode" class="button button--ghost" type="button" @click="emit('go-signup')">회원가입으로 돌아가기</button>
        <button class="button button--primary" type="button" @click="emit('go-login')">로그인으로 이동</button>
      </div>
    </article>
  </section>
</template>
