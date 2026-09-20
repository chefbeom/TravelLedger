<script setup>
import { reactive, ref } from 'vue'
import PinPadInput from './PinPadInput.vue'

defineProps({
  submitting: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['complete', 'go-login'])
const form = reactive({
  loginId: '',
  displayName: '',
  password: '',
  passwordConfirmation: '',
  secondaryPin: '',
  rememberDevice: true,
})
const validationMessage = ref('')

function submit() {
  validationMessage.value = ''
  if (form.password !== form.passwordConfirmation) {
    validationMessage.value = '비밀번호 확인이 일치하지 않습니다.'
    return
  }
  emit('complete', {
    loginId: form.loginId.trim(),
    displayName: form.displayName.trim(),
    password: form.password,
    secondaryPin: form.secondaryPin,
    rememberDevice: form.rememberDevice,
  })
}
</script>

<template>
  <section class="auth-shell">
    <div class="auth-copy">
      <span class="auth-copy__badge">카카오 회원가입</span>
      <h1>TravelLedger 계정을 연결하세요.</h1>
      <p>카카오 이메일 인증이 확인되었습니다. TravelLedger에서 사용할 로그인 ID와 보안 정보를 정해 주세요.</p>
    </div>

    <article class="auth-card">
      <h2>계정 정보 입력</h2>
      <form class="stack-form" @submit.prevent="submit">
        <input v-model="form.loginId" type="text" placeholder="로그인 ID" autocomplete="username" :disabled="submitting" required />
        <input v-model="form.displayName" type="text" placeholder="표시 이름" autocomplete="name" :disabled="submitting" required />
        <input v-model="form.password" type="password" placeholder="비밀번호(8자 이상)" autocomplete="new-password" minlength="8" :disabled="submitting" required />
        <input v-model="form.passwordConfirmation" type="password" placeholder="비밀번호 확인" autocomplete="new-password" minlength="8" :disabled="submitting" required />
        <PinPadInput
          v-model="form.secondaryPin"
          label="2차 비밀번호"
          hint="로그인할 때 사용할 숫자 8자리를 입력해 주세요."
          :disabled="submitting"
        />
        <label class="checkbox-row">
          <input v-model="form.rememberDevice" type="checkbox" :disabled="submitting" />
          <span>이 기기에서 로그인 상태 유지</span>
        </label>
        <p v-if="validationMessage" class="feedback feedback--error" role="alert">{{ validationMessage }}</p>
        <button class="button button--primary" type="submit" :disabled="submitting">
          {{ submitting ? '계정 연결 중...' : '카카오 계정 연결' }}
        </button>
        <button class="button button--ghost" type="button" :disabled="submitting" @click="emit('go-login')">로그인으로 돌아가기</button>
      </form>
    </article>
  </section>
</template>
