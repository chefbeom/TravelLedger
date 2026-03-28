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
  feedbackMessage: {
    type: String,
    default: '',
  },
  errorMessage: {
    type: String,
    default: '',
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

  return new Intl.DateTimeFormat('en-US', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(normalized)
}
</script>

<template>
  <section class="panel invite-panel">
    <div class="panel__header">
      <div>
        <h2>초대 링크 만들기</h2>
        <p>공개 회원가입은 계속 비활성화되어 있고, 이 1회용 링크를 받은 사람만 새 계정을 만들 수 있습니다.</p>
      </div>
      <span class="panel__badge">초대 전용</span>
    </div>

    <div class="invite-panel__controls">
      <label class="field">
        <span class="field__label">링크 유효 시간</span>
        <select
          :value="expiresInHours"
          :disabled="isCreating"
          @change="emit('change-expiry', Number($event.target.value))"
        >
          <option v-for="option in expiryOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>

      <div class="invite-panel__actions">
        <button class="button button--primary" :disabled="isCreating" @click="emit('create-invite')">
          {{ isCreating ? '생성 중...' : '초대 링크 만들기' }}
        </button>
        <button class="button button--ghost" :disabled="!generatedLink" @click="emit('copy-invite')">
          링크 복사
        </button>
      </div>
    </div>

    <div v-if="generatedLink" class="invite-panel__result">
      <label class="field field--full">
        <span class="field__label">생성된 링크</span>
        <input :value="generatedLink" readonly />
      </label>
      <p class="invite-panel__meta">만료 시간: {{ formatDateTime(generatedExpiresAt) }}</p>
    </div>

    <div v-if="feedbackMessage" class="feedback feedback--success">{{ feedbackMessage }}</div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>
  </section>
</template>
