<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: {
    type: String,
    default: '',
  },
  label: {
    type: String,
    default: '2차 비밀번호',
  },
  hint: {
    type: String,
    default: '키보드 대신 아래 숫자를 눌러 8자리를 입력해주세요.',
  },
  disabled: {
    type: Boolean,
    default: false,
  },
  length: {
    type: Number,
    default: 8,
  },
})

const emit = defineEmits(['update:modelValue'])

const slots = computed(() => Array.from({ length: props.length }, (_, index) => props.modelValue[index] || ''))

const keypadRows = [
  ['1', '2', '3'],
  ['4', '5', '6'],
  ['7', '8', '9'],
]

function appendDigit(digit) {
  if (props.disabled || props.modelValue.length >= props.length) {
    return
  }
  emit('update:modelValue', `${props.modelValue}${digit}`)
}

function removeDigit() {
  if (props.disabled || !props.modelValue.length) {
    return
  }
  emit('update:modelValue', props.modelValue.slice(0, -1))
}

function clearDigits() {
  if (props.disabled || !props.modelValue.length) {
    return
  }
  emit('update:modelValue', '')
}
</script>

<template>
  <div class="pin-pad" :class="{ 'pin-pad--disabled': disabled }">
    <div class="pin-pad__header">
      <span class="field__label">{{ label }}</span>
      <span class="pin-pad__progress">{{ modelValue.length }}/{{ length }}자리</span>
    </div>

    <div class="pin-pad__display" @keydown.prevent>
      <span
        v-for="(slot, index) in slots"
        :key="index"
        class="pin-pad__slot"
        :class="{ 'pin-pad__slot--filled': slot }"
      >
        {{ slot ? '●' : '' }}
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
          :disabled="disabled"
          @click="appendDigit(digit)"
        >
          {{ digit }}
        </button>
      </template>
      <button class="pin-pad__key pin-pad__key--ghost" type="button" :disabled="disabled" @click="clearDigits">
        전체삭제
      </button>
      <button class="pin-pad__key" type="button" :disabled="disabled" @click="appendDigit('0')">0</button>
      <button class="pin-pad__key pin-pad__key--ghost" type="button" :disabled="disabled" @click="removeDigit">
        지우기
      </button>
    </div>
  </div>
</template>
