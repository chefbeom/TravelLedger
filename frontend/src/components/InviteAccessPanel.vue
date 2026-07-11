<script setup>
defineProps({
  expiresInHours: {
    type: Number,
    default: 72,
  },
  generatedLink: {
    type: String,
    default: '',
  },
  generatedExpiresAt: {
    type: String,
    default: '',
  },
  isCreating: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['change-expiry', 'create-invite', 'copy-invite'])

const expiryOptions = [
  { value: 24, label: '24시간' },
  { value: 72, label: '72시간' },
  { value: 168, label: '7일' },
]

function formatDateTime(value) {
  if (!value) {
    return '-'
  }

  const normalized = new Date(value)
  if (Number.isNaN(normalized.getTime())) {
    return String(value)
  }

  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(normalized)
}
</script>

<template>
  <section class="panel invite-panel invite-panel--compact" aria-labelledby="invite-panel-title">
    <div class="panel__header invite-panel__header">
      <h2 id="invite-panel-title">초대 링크 생성</h2>
    </div>

    <div class="invite-panel__controls">
      <label class="field">
        <span class="field__label">유효 기간</span>
        <select
          :value="expiresInHours"
          :disabled="isCreating"
          aria-label="초대 링크 유효 기간"
          @change="emit('change-expiry', Number($event.target.value))"
        >
          <option v-for="option in expiryOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>

      <div class="invite-panel__actions">
        <button class="button button--primary" type="button" :disabled="isCreating" @click="emit('create-invite')">
          {{ isCreating ? '생성 중...' : '초대 링크 생성' }}
        </button>
        <button class="button button--ghost" type="button" :disabled="!generatedLink" @click="emit('copy-invite')">
          복사
        </button>
      </div>
    </div>

    <div v-if="generatedLink" class="invite-panel__result" role="status" aria-live="polite">
      <label class="field field--full">
        <span class="field__label">생성된 링크</span>
        <input :value="generatedLink" readonly aria-label="생성된 초대 링크" />
      </label>
      <p class="invite-panel__meta">만료 시각: {{ formatDateTime(generatedExpiresAt) }}</p>
    </div>
  </section>
</template>
