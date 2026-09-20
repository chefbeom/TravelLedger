<script setup>
import { ref, watch } from 'vue'
import { verifyEmail } from '../lib/sessionApi'

const props = defineProps({
  token: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['go-login', 'go-signup'])
const status = ref('loading')
const message = ref('이메일 인증 링크를 확인하는 중입니다...')

async function verify() {
  if (!props.token) {
    status.value = 'error'
    message.value = '이메일 인증 링크가 없습니다.'
    return
  }
  status.value = 'loading'
  message.value = '이메일 인증 링크를 확인하는 중입니다...'
  try {
    const response = await verifyEmail({ token: props.token })
    status.value = response?.verified ? 'success' : 'error'
    message.value = response?.message || '이메일 인증 결과를 확인할 수 없습니다.'
  } catch (error) {
    status.value = 'error'
    message.value = error.message
  }
}

watch(() => props.token, verify, { immediate: true })
</script>

<template>
  <section class="auth-shell">
    <div class="auth-copy">
      <span class="auth-copy__badge">이메일 인증</span>
      <h1>가입을 마무리하세요.</h1>
      <p>이메일 인증 링크는 한 번만 사용할 수 있고 설정된 시간 동안만 유효합니다.</p>
    </div>

    <article class="auth-card">
      <h2>{{ status === 'loading' ? '인증 확인 중' : status === 'success' ? '인증 완료' : '인증할 수 없습니다' }}</h2>
      <p :class="status === 'success' ? 'feedback feedback--success' : status === 'error' ? 'feedback feedback--error' : ''" role="status">
        {{ message }}
      </p>
      <div class="panel__actions">
        <button v-if="status === 'error'" class="button button--ghost" type="button" @click="emit('go-signup')">회원가입으로 돌아가기</button>
        <button class="button button--primary" type="button" @click="emit('go-login')">로그인으로 이동</button>
      </div>
    </article>
  </section>
</template>
