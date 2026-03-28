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
  { value: 24, label: '24 hours' },
  { value: 72, label: '72 hours' },
  { value: 168, label: '7 days' },
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
        <h2>Create an invite link</h2>
        <p>Open signup stays disabled. Only people with this one-time link can create a new account.</p>
      </div>
      <span class="panel__badge">Invite Only</span>
    </div>

    <div class="invite-panel__controls">
      <label class="field">
        <span class="field__label">Link lifetime</span>
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
          {{ isCreating ? 'Creating...' : 'Create invite link' }}
        </button>
        <button class="button button--ghost" :disabled="!generatedLink" @click="emit('copy-invite')">
          Copy link
        </button>
      </div>
    </div>

    <div v-if="generatedLink" class="invite-panel__result">
      <label class="field field--full">
        <span class="field__label">Generated link</span>
        <input :value="generatedLink" readonly />
      </label>
      <p class="invite-panel__meta">Expires at: {{ formatDateTime(generatedExpiresAt) }}</p>
    </div>

    <div v-if="feedbackMessage" class="feedback feedback--success">{{ feedbackMessage }}</div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>
  </section>
</template>
