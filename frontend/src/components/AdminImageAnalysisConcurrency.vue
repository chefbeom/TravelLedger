<script setup>
import { onMounted, ref } from 'vue'
import { fetchAdminImageConcurrency, updateAdminImageConcurrency } from '../lib/api'

const loading = ref(true), saving = ref(false), limit = ref(3), savedLimit = ref(3)
const loaded = ref(false)
const error = ref(''), message = ref('')
async function load() {
  loading.value = true
  error.value = ''
  try {
    const settings = await fetchAdminImageConcurrency()
    limit.value = savedLimit.value = settings.maxConcurrent
    loaded.value = true
  } catch (failure) {
    error.value = failure.message || '동시 분석 수 설정을 불러오지 못했습니다.'
  } finally { loading.value = false }
}
async function save() {
  error.value = message.value = ''
  if (!Number.isInteger(limit.value) || limit.value < 1 || limit.value > 20) {
    error.value = '1~20 사이의 정수를 입력해 주세요.'
    return
  }
  saving.value = true
  try {
    const settings = await updateAdminImageConcurrency(limit.value)
    limit.value = savedLimit.value = settings.maxConcurrent
    message.value = `최대 동시 분석 수를 ${settings.maxConcurrent}건으로 저장했습니다.`
  } catch (failure) {
    error.value = failure.message || '동시 분석 수를 저장하지 못했습니다.'
  } finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <section class="admin-ops-panel admin-image-concurrency" aria-labelledby="admin-image-concurrency-title">
    <div class="panel__header">
      <div><h3 id="admin-image-concurrency-title">이미지 분석 동시 수</h3><p>모든 사용자의 이미지 분석에 적용하는 서버 공통 한도입니다. 기본값은 3건입니다.</p></div>
      <button type="button" class="button button--ghost" :disabled="loading || saving" @click="load">설정 새로고침</button>
    </div>
    <form @submit.prevent="save">
      <label class="field"><span class="field__label">최대 동시 이미지 분석 수</span><input v-model.number="limit" type="number" min="1" max="20" step="1" required :disabled="loading || saving" /></label>
      <p>1~20건 사이에서 지정합니다. 이미 진행 중인 분석은 중단하지 않으며, 한도를 낮추면 진행 중인 작업이 끝나면서 새 한도로 전환됩니다. 사용자 화면에서는 이 값을 변경할 수 없습니다.</p>
      <p>브라우저는 새 요청 묶음을 시작할 때 최신 설정을 불러옵니다. 서버 인스턴스는 5초 주기로 설정을 갱신하며, AI 제공자 자체 한도가 더 낮으면 그 한도가 우선합니다.</p>
      <p v-if="loaded">현재 저장값: {{ savedLimit }}건</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
      <button type="submit" class="button button--primary" :disabled="loading || saving || !loaded">{{ saving ? '저장 중...' : '동시 분석 수 저장' }}</button>
    </form>
  </section>
</template>

<style scoped>
.admin-image-concurrency form { display: grid; gap: 16px; min-width: 0; }
.admin-image-concurrency .field { max-width: 320px; }
.admin-image-concurrency input { width: 100%; box-sizing: border-box; min-height: 44px; }
.admin-image-concurrency p { margin: 0; line-height: 1.6; overflow-wrap: anywhere; }
.admin-image-concurrency button[type=submit] { justify-self: start; min-height: 44px; }
.admin-image-concurrency [role=alert] { color: var(--expense); }
</style>
