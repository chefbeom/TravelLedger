<script setup>
import { computed, reactive, ref, watch } from 'vue'
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
  resending: {
    type: Boolean,
    default: false,
  },
  verificationRequested: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['register', 'go-login', 'resend-email', 'social-login'])

const form = reactive({
  loginId: '',
  displayName: '',
  email: '',
  password: '',
  passwordConfirmation: '',
  secondaryPin: '',
  rememberDevice: true,
})
const validationMessage = ref('')
const resendAddress = ref('')
watch(() => props.verificationRequested, requested => {
  if (requested) resendAddress.value = form.email.trim()
}, { immediate: true })
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
    email: form.email.trim(),
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
            <p class="auth-copy__hint">이메일 인증 링크에서 가입 시 설정한 2차 비밀번호를 다시 입력합니다. 새 미인증 가입 신청은 마지막 인증 메일 요청 후 7일이 지나면 만료됩니다.</p>
            <input v-model="form.loginId" type="text" placeholder="로그인 ID" autocomplete="username" :disabled="submitting" required />
            <input v-model="form.displayName" type="text" placeholder="표시 이름" autocomplete="name" :disabled="submitting" required />
            <input v-model="form.email" type="email" placeholder="이메일" autocomplete="email" :disabled="submitting" required />
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
            <button class="button button--primary" type="submit" :disabled="submitting || resending">
              {{ submitting ? '인증 메일 보내는 중...' : '인증 메일 보내기' }}
            </button>
            <p v-if="verificationRequested" class="feedback feedback--success" role="status">
              인증 메일을 보냈습니다. 메일의 링크를 열어 가입을 완료해 주세요.
            </p>
          </form>
        </template>
        <div v-else class="stack-form stack-form--readonly">
          <p>현재는 초대 링크로만 가입할 수 있습니다.</p>
          <p>관리자가 공개 회원가입을 켜면 이 페이지에서 바로 계정을 만들 수 있습니다.</p>
        </div>
        <details :open="verificationRequested" class="stack-form">
          <summary>이미 가입을 신청하셨나요? 인증 메일 다시 받기</summary>
          <form class="stack-form" @submit.prevent="emit('resend-email', resendAddress.trim())">
            <label for="signup-resend-email">가입에 사용한 이메일</label>
            <input id="signup-resend-email" v-model="resendAddress" type="email" autocomplete="email"
              :disabled="submitting || resending" required />
            <p class="auth-copy__hint">메일은 60초 간격으로 요청할 수 있습니다. 마지막 발송 요청 후 7일이 지난 신청은 다시 가입해 주세요.</p>
            <button class="button button--ghost" type="submit" :disabled="submitting || resending">
              {{ resending ? '재전송 요청 중...' : '인증 메일 다시 보내기' }}
            </button>
          </form>
        </details>
      </article>

      <article class="auth-card">
        <h2>카카오로 가입·로그인</h2>
        <div v-if="socialLoginProviders.length" class="stack-form stack-form--readonly">
          <p v-if="options?.publicRegistrationEnabled">카카오 인증 후 이메일을 다시 인증할 필요 없이 서비스 계정을 만들 수 있습니다.</p>
          <p v-else>신규 가입은 닫혀 있습니다. 이미 연결한 카카오 계정은 계속 로그인할 수 있습니다.</p>
          <button
            v-for="provider in socialLoginProviders"
            :key="provider"
            :class="['button', 'button--ghost', { 'button--kakao': provider === 'KAKAO' }]"
            type="button"
            :disabled="submitting || loading"
            @click="emit('social-login', provider)"
          >
            {{ provider === 'KAKAO' ? '카카오로 계속' : `${provider}로 계속` }}
          </button>
        </div>
        <div v-else class="stack-form stack-form--readonly">
          <p>현재 사용할 수 있는 소셜 로그인 제공자가 없습니다.</p>
          <p>카카오 연결 설정이 완료되면 이곳에서 가입·로그인할 수 있습니다.</p>
        </div>
        <div class="panel__actions">
          <button class="button button--ghost" type="button" @click="emit('go-login')">로그인으로 돌아가기</button>
        </div>
      </article>
    </div>
  </section>
</template>
