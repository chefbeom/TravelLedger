<script setup>
import { nextTick, reactive, ref } from 'vue'
import { validCalendarDate } from '../../lib/integratedCalendarCore.js'
import BaseDialog from './BaseDialog.vue'

const props = defineProps({ initial: { type: Object, required: true }, busy: { type: Boolean, default: false }, error: { type: String, default: '' } })
const emit = defineEmits(['submit', 'delete', 'close'])
const draft = reactive({ ...props.initial })
const localError = ref('')
const form = ref(null)

async function submit() {
  localError.value = ''
  const endDate = draft.endDate || draft.startDate
  const titleInvalid = !draft.title.trim()
  const datesInvalid = !validCalendarDate(draft.startDate) || !validCalendarDate(endDate) || endDate < draft.startDate
  const timeInvalid = !draft.allDay && draft.startDate === endDate && draft.startTime && draft.endTime && draft.endTime < draft.startTime
  if (titleInvalid || datesInvalid || timeInvalid) {
    localError.value = '제목과 시작·종료 날짜 및 시간을 확인해 주세요.'
    await nextTick()
    const target = titleInvalid ? form.value.querySelector('[name="title"]') : datesInvalid ? form.value.querySelector('[name="startDate"]') : form.value.querySelector('[name="endTime"]')
    target?.focus()
    return
  }
  emit('submit', { ...draft })
}
</script>

<template>
  <BaseDialog variant="center" labelledby="integrated-calendar-event-title" :busy="busy" @close="emit('close')">
    <form ref="form" class="integrated-calendar__form" novalidate @submit.prevent="submit">
      <h2 id="integrated-calendar-event-title">{{ draft.id ? '일정 수정' : '개인 일정 추가' }}</h2>
      <p v-if="localError || error" role="alert" class="integrated-calendar__form-error">{{ localError || error }}</p>
      <label>제목 <input v-model="draft.title" name="title" maxlength="160" autofocus></label>
      <label>시작 날짜 <input v-model="draft.startDate" name="startDate" type="date"></label>
      <label>종료 날짜 (선택) <input v-model="draft.endDate" name="endDate" type="date"></label>
      <label class="integrated-calendar__form-check"><input v-model="draft.allDay" type="checkbox"> 종일</label>
      <template v-if="!draft.allDay">
        <label>시작 시간 <input v-model="draft.startTime" name="startTime" type="time"></label>
        <label>종료 시간 <input v-model="draft.endTime" name="endTime" type="time"></label>
      </template>
      <label>메모 <textarea v-model="draft.memo" maxlength="4000" rows="3"></textarea></label>
      <div class="integrated-calendar__form-actions">
        <button v-if="draft.id" type="button" class="button button--danger" :disabled="busy" @click="emit('delete')">삭제</button>
        <span class="integrated-calendar__form-spacer" />
        <button type="button" class="button button--ghost" :disabled="busy" @click="emit('close')">취소</button>
        <button type="submit" class="button button--primary" :disabled="busy">저장</button>
      </div>
    </form>
  </BaseDialog>
</template>

<style scoped>
.integrated-calendar__form { display: grid; gap: .75rem; padding: 1.25rem; }
.integrated-calendar__form h2, .integrated-calendar__form p { margin: 0; }
.integrated-calendar__form h2 { font-size: 1.125rem; }
.integrated-calendar__form label { display: grid; gap: .25rem; font-size: .9375rem; }
.integrated-calendar__form input:not([type='checkbox']), .integrated-calendar__form textarea { box-sizing: border-box; width: 100%; min-width: 0; min-height: 2.75rem; padding: .4rem .6rem; font: inherit; }
.integrated-calendar__form .integrated-calendar__form-check { display: flex; align-items: center; gap: .5rem; min-height: 2.75rem; }
.integrated-calendar__form input[type='checkbox'] { width: 1.1rem; height: 1.1rem; }
.integrated-calendar .integrated-calendar__form-error { padding: .5rem .75rem; border: 1px solid var(--ic-warning); border-left-width: 3px; background: var(--ic-warning-soft); }
.integrated-calendar__form-actions { display: flex; align-items: center; flex-wrap: wrap; gap: .5rem; }
.integrated-calendar__form-spacer { flex: 1 1 auto; }
.integrated-calendar__form-actions .button { min-height: 2.75rem; }
</style>
