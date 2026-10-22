<script setup>
import { computed, ref, watch } from 'vue'
import { buildLedgerImageAnalysisImageUrl } from '../lib/api'

const props = defineProps({ history: { type: Object, required: true } })
const failed = ref(false)
const imageUrl = computed(() => {
  const history = props.history
  const available = history.imageAvailable === true || (history.imageAvailable !== false && Boolean(history.imageUrl))
  return available && history.id ? buildLedgerImageAnalysisImageUrl(history.id) : ''
})
watch(imageUrl, () => { failed.value = false })
</script>

<template>
  <div class="receipt-history-thumbnail">
    <img
      v-if="imageUrl && !failed"
      :src="imageUrl"
      :alt="history.fileName || '분석 요청 이미지'"
      loading="lazy"
      decoding="async"
      @error="failed = true"
    />
    <div v-else class="receipt-history-thumbnail__fallback" role="img" :aria-label="failed ? '이미지 미리보기 불가' : '저장된 이미지 없음'">
      <svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><rect x="4" y="5" width="24" height="22" rx="2" stroke="currentColor" stroke-width="1.5"/><circle cx="11" cy="12" r="2" fill="currentColor"/><path d="m5 23 8-8 5 5 4-4 5 6" stroke="currentColor" stroke-width="1.5"/></svg>
      <p>{{ failed ? '미리보기를 불러오지 못했습니다' : '저장된 이미지가 없습니다' }}</p>
    </div>
  </div>
</template>
