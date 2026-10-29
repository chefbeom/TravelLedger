<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: {
    type: String,
    default: '',
  },
  label: {
    type: String,
    default: 'Secondary PIN',
  },
  hint: {
    type: String,
    default: 'Use the numeric keypad to enter 8 digits.',
  },
  disabled: {
    type: Boolean,
    default: false,
  },
  length: {
    type: Number,
    default: 8,
  },
  loginAppearance: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['update:modelValue'])

const slots = computed(() => Array.from({ length: props.length }, (_, index) => props.modelValue[index] || ''))
const progressLabel = computed(() => props.loginAppearance
  ? `2차 비밀번호 ${props.length}자리 중 ${props.modelValue.length}자리 입력됨`
  : `${props.modelValue.length} of ${props.length} PIN digits entered`)
const digitDisabled = computed(() => props.disabled || props.modelValue.length >= props.length)
const editDisabled = computed(() => props.disabled || !props.modelValue.length)

const keypadRows = [
  ['1', '2', '3'],
  ['4', '5', '6'],
  ['7', '8', '9'],
]

function appendDigit(digit) {
  if (digitDisabled.value) {
    return
  }
  emit('update:modelValue', `${props.modelValue}${digit}`)
}

function removeDigit() {
  if (editDisabled.value) {
    return
  }
  emit('update:modelValue', props.modelValue.slice(0, -1))
}

function clearDigits() {
  if (editDisabled.value) {
    return
  }
  emit('update:modelValue', '')
}
</script>

<template>
  <div class="pin-pad" :class="{ 'pin-pad--disabled': disabled }" role="group" :aria-label="label">
    <div class="pin-pad__header">
      <span class="field__label">{{ label }}</span>
      <span class="pin-pad__progress">{{ modelValue.length }} / {{ length }}{{ loginAppearance ? '' : ' digits' }}</span>
    </div>

    <div class="pin-pad__display" role="status" aria-live="polite" :aria-label="progressLabel" @keydown.prevent>
      <span
        v-for="(slot, index) in slots"
        :key="index"
        class="pin-pad__slot"
        :class="{ 'pin-pad__slot--filled': slot }"
      >
        {{ slot ? '*' : '' }}
      </span>
    </div>

    <p class="pin-pad__hint">{{ hint }}</p>

    <div class="pin-pad__grid">
      <template v-for="row in keypadRows" :key="row.join('-')">
        <button
          v-for="digit in row"
          :key="digit"
          class="pin-pad__key"
          type="button"
          :disabled="digitDisabled"
          :aria-label="loginAppearance ? `2차 비밀번호 ${digit}` : `PIN digit ${digit}`"
          @click="appendDigit(digit)"
        >
          {{ digit }}
        </button>
      </template>
      <button class="pin-pad__key pin-pad__key--ghost" type="button" :disabled="editDisabled" :aria-label="loginAppearance ? '2차 비밀번호 모두 지우기' : 'Clear PIN digits'" @click="clearDigits">
        {{ loginAppearance ? '지우기' : 'Clear' }}
      </button>
      <button class="pin-pad__key" type="button" :disabled="digitDisabled" :aria-label="loginAppearance ? '2차 비밀번호 0' : 'PIN digit 0'" @click="appendDigit('0')">0</button>
      <button class="pin-pad__key pin-pad__key--ghost" type="button" :disabled="editDisabled" :aria-label="loginAppearance ? '2차 비밀번호 한 자리 지우기' : 'Delete last PIN digit'" @click="removeDigit">
        {{ loginAppearance ? '←' : 'Delete' }}
      </button>
    </div>
  </div>
</template>
