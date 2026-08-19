<script setup>
import { computed, reactive, ref } from 'vue'
import PinPadInput from './PinPadInput.vue'

const props = defineProps({
  options: {
    type: Object,
    default: () => ({ publicRegistrationEnabled: false, socialLoginProviders: [] }),
  },
  loading: {
    type: Boolean,
    default: false,
  },
  submitting: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['register', 'go-login'])

const form = reactive({
  loginId: '',
  displayName: '',
  password: '',
  passwordConfirmation: '',
  secondaryPin: '',
  rememberDevice: true,
})
const validationMessage = ref('')
const socialLoginProviders = computed(() => Array.isArray(props.options?.socialLoginProviders)
  ? props.options.socialLoginProviders
  : [])

function submitRegistration() {
  validationMessage.value = ''
  if (form.password !== form.passwordConfirmation) {
    validationMessage.value = '비밀번호 확인이 일치하지 않습니다.'
    return
  }

  emit('register', {
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
      <span class="auth-copy__badge">공개 회원가입</span>
      <h1>TravelLedger 계정을 만드세요.</h1>
      <p>관리자가 공개 가입을 켠 기간에만 새 계정을 만들 수 있습니다. 계정은 기본 가계부와 대시보드 설정을 포함해 준비됩니다.</p>
      <p class="auth-copy__hint">공개 가입을 다시 끄면 초대 링크 가입만 가능해집니다.</p>
    </div>

    <div class="auth-grid">
      <article class="auth-card">
        <h2>회원가입</h2>
        <p v-if="loading">가입 가능 여부를 확인하는 중입니다...</p>
        <template v-else-if="options?.publicRegistrationEnabled">
          <form class="stack-form" @submit.prevent="submitRegistration">
            <input v-model="form.loginId" type="text" placeholder="로그인 ID" autocomplete="username" :disabled="submitting" required />
            <input v-model="form.displayName" type="text" placeholder="표시 이름" autocomplete="name" :disabled="submitting" required />
            <input v-model="form.password" type="password" placeholder="비밀번호(8자 이상)" autocomplete="new-password" :disabled="submitting" minlength="8" required />
            <input v-model="form.passwordConfirmation" type="password" placeholder="비밀번호 확인" autocomplete="new-password" :disabled="submitting" minlength="8" required />
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
              {{ submitting ? '계정 생성 중...' : '회원가입하고 로그인' }}
            </button>
          </form>
        </template>
        <div v-else class="stack-form stack-form--readonly">
          <p>현재는 초대 링크로만 가입할 수 있습니다.</p>
          <p>관리자가 공개 회원가입을 켜면 이 페이지에서 바로 계정을 만들 수 있습니다.</p>
        </div>
      </article>

      <article class="auth-card">
        <h2>로그인 및 소셜 로그인</h2>
        <div v-if="socialLoginProviders.length" class="stack-form stack-form--readonly">
          <p>연결된 소셜 로그인 제공자</p>
          <button v-for="provider in socialLoginProviders" :key="provider" class="button button--ghost" type="button" disabled>
            {{ provider }}로 계속
          </button>
        </div>
        <div v-else class="stack-form stack-form--readonly">
          <p>아직 연결된 소셜 로그인 제공자가 없습니다.</p>
          <p>추후 Google·Kakao 등 제공자를 연결하면, 이 가입 정책을 그대로 적용해 이 영역에 표시됩니다.</p>
        </div>
        <div class="panel__actions">
          <button class="button button--ghost" type="button" @click="emit('go-login')">로그인으로 돌아가기</button>
        </div>
      </article>
    </div>
  </section>
</template>
