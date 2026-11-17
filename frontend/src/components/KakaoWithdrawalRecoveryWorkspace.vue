<script setup>
import { reactive, watch } from 'vue'
import WithdrawalSelectionForm from './WithdrawalSelectionForm.vue'
import { previewKakaoWithdrawalRecovery, requestKakaoWithdrawalRecovery, startKakaoWithdrawalRecovery } from '../lib/api'

const props = defineProps({ token: { type: String, default: '' }, authenticated: { type: Boolean, default: false } })
const emit = defineEmits(['go-login', 'logout', 'withdrawal-requested'])
const state = reactive({ email: '', pin: '', busy: false, error: '', message: '', preview: null })

watch(() => props.token, () => { state.pin = ''; state.preview = null; state.error = ''; state.message = '' })

async function requestProof() {
  if (state.busy) return
  state.busy = true; state.error = ''; state.message = ''
  try {
    const result = await requestKakaoWithdrawalRecovery(state.email.trim())
    state.message = result?.message || '요청을 처리했습니다. 해당하는 계정이 있다면 인증 메일을 확인해 주세요.'
  } catch (error) {
    state.error = error?.message || '요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  } finally { state.busy = false }
}

async function preview() {
  if (state.busy || !/^\d{8}$/.test(state.pin)) return
  state.busy = true; state.error = ''; state.preview = null
  try { state.preview = await previewKakaoWithdrawalRecovery(props.token, state.pin) }
  catch (error) { state.error = error?.message || '이메일 인증을 확인할 수 없습니다.' }
  finally { state.pin = ''; state.busy = false }
}

async function submit(payload) {
  if (state.busy || state.preview?.withdrawalAvailable !== true) return
  state.busy = true; state.error = ''
  try {
    const result = await startKakaoWithdrawalRecovery(props.token, payload)
    if (result?.accepted !== true) throw new Error('요청 결과를 확인할 수 없습니다. 다시 확인해 주세요.')
    emit('withdrawal-requested')
  } catch (error) { state.error = error?.message || '탈퇴 요청을 확인할 수 없습니다. 다시 확인해 주세요.' }
  finally { state.busy = false }
}
</script>

<template>
  <section class="auth-shell">
    <div class="auth-copy">
      <span class="auth-copy__badge">카카오 연결 해제</span>
      <h1>인증 후 탈퇴를 확인하세요.</h1>
      <p>로그인할 수 없는 카카오 전용 계정의 탈퇴를 위해 인증 이메일과 2차 비밀번호를 확인합니다.</p>
      <p class="auth-copy__hint">이메일 확인이나 공유 항목 조회만으로는 계정이 삭제되지 않습니다.</p>
    </div>
      <article class="auth-card stack-form">
        <h1>카카오 계정 탈퇴 확인</h1>
        <p class="feedback" role="status">격리 테스트 환경에서만 사용할 수 있습니다. 탈퇴 접수는 전체 자료 삭제 완료를 뜻하지 않습니다.</p>
        <p>카카오 연결이 해제되어 로그인할 수 없는 카카오 전용 계정은 인증 이메일과 2차 비밀번호를 확인한 뒤 탈퇴를 요청할 수 있습니다.</p>
        <p>이 화면은 로그인 수단을 만들거나 이메일 확인만으로 자료를 삭제하지 않습니다.</p>
        <div v-if="authenticated" class="stack-form">
          <p>다른 계정이 로그인되어 있습니다. 로그아웃한 뒤 이메일 링크를 다시 열어 주세요.</p>
          <button class="button button--ghost" type="button" @click="emit('logout')">로그아웃</button>
        </div>
        <form v-else-if="!token" class="stack-form" @submit.prevent="requestProof">
          <label class="field"><span class="field__label">가입된 인증 이메일</span>
            <input v-model.trim="state.email" type="email" autocomplete="email" maxlength="254" required :disabled="state.busy" />
          </label>
          <button class="button button--primary" type="submit" :disabled="state.busy">{{ state.busy ? '요청 중...' : '확인 메일 요청' }}</button>
        </form>
        <template v-else>
          <p>공유 가족 앨범·사진과 공유 그룹은 선택한 활성 멤버에게 이전하며, 공유 여행은 선택한 기존 공유 멤버에게 이전합니다. 공개만 된 여행은 먼저 다른 회원에게 공유해야 합니다.</p>
          <p>다른 회원의 여행에 올린 사진·영수증은 해당 여행에 보존합니다. 기존 공개 전시·커뮤니티 공개와 공개 지도 링크는 해제합니다.</p>
          <p>운영 데이터 정리와 카카오 연결 확인은 요청 후 진행됩니다. 전체 백업의 30일 이내 폐기는 목표이며 아직 자동화되지 않았습니다.</p>
          <form class="stack-form" @submit.prevent="preview">
            <label class="field"><span class="field__label">2차 비밀번호</span>
              <input v-model.trim="state.pin" type="password" inputmode="numeric" autocomplete="one-time-code" maxlength="8" :disabled="state.busy" />
            </label>
            <button class="button button--primary" type="submit" :disabled="state.busy || !/^\d{8}$/.test(state.pin)">{{ state.busy ? '확인 중...' : '공유 항목 확인' }}</button>
          </form>
          <div v-if="state.preview" class="stack-form">
            <p>가족 앨범 {{ state.preview.requiredFamilyTransfers?.length ?? '?' }}개 · 공유 그룹 {{ state.preview.requiredShareGroupTransfers?.length ?? '?' }}개 · 여행 계획 {{ state.preview.requiredTravelPlanTransfers?.length ?? '?' }}개</p>
            <p>다른 회원의 여행에 남을 내 사진·영수증 {{ state.preview.foreignTravelMedia?.mediaCount ?? '?' }}개</p>
            <p>사전 확인과 대상 선택만으로는 탈퇴가 진행되지 않습니다.</p>
            <WithdrawalSelectionForm :preview="state.preview" :busy="state.busy" :error="state.error" @submit="submit" />
          </div>
        </template>
        <p v-if="state.message" class="feedback feedback--success" role="status">{{ state.message }}</p>
        <p v-if="state.error && !state.preview" class="feedback feedback--error" role="alert">{{ state.error }}</p>
        <button class="button button--ghost" type="button" :disabled="state.busy" @click="emit('go-login')">로그인으로</button>
      </article>
  </section>
</template>
