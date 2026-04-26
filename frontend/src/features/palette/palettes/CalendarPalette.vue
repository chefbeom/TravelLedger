<script setup>
import { computed } from 'vue'

const props = defineProps({
  config: {
    type: Object,
    required: true,
  },
  data: {
    type: Object,
    default: () => ({}),
  },
})

const weekdayLabels = ['월', '화', '수', '목', '금', '토', '일']
const flatDays = computed(() => {
  const days = (props.data.weeks ?? []).flat().slice(0, 42)
  if (days.length >= 42) {
    return days
  }
  return [
    ...days,
    ...Array.from({ length: 42 - days.length }, (_, index) => ({
      date: `empty-${index}`,
      dayNumber: '',
      inCurrentMonth: false,
      summary: { entryCount: 0, income: 0, expense: 0 },
    })),
  ]
})

function dayTone(day) {
  const income = Number(day?.summary?.income ?? 0)
  const expense = Number(day?.summary?.expense ?? 0)
  if (income > expense) return 'positive'
  if (expense > income) return 'negative'
  if (income || expense) return 'neutral'
  return 'empty'
}
</script>

<template>
  <div class="calendar-palette" :class="`calendar-palette--${config.size}`">
    <div class="calendar-palette__head">
      <span>월 달력</span>
      <strong>{{ data.monthLabel || '-' }}</strong>
    </div>

    <div class="calendar-palette__grid" aria-label="월 달력 축소판">
      <div v-for="label in weekdayLabels" :key="label" class="calendar-palette__weekday">
        {{ label }}
      </div>
      <div
        v-for="day in flatDays"
        :key="day.date"
        class="calendar-palette__day"
        :class="[
          day.inCurrentMonth ? 'is-current' : 'is-muted',
          `is-${dayTone(day)}`,
        ]"
      >
        <span class="calendar-palette__number">{{ day.dayNumber }}</span>
        <span v-if="Number(day.summary?.entryCount || 0)" class="calendar-palette__count">
          {{ day.summary.entryCount }}
        </span>
        <span class="calendar-palette__marker"></span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.calendar-palette {
  display: grid;
  gap: 8px;
  grid-template-rows: auto minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.calendar-palette__head {
  align-items: end;
  display: flex;
  gap: 8px;
  justify-content: space-between;
  min-width: 0;
}

.calendar-palette__head span {
  color: #6f42c1;
  font-size: 0.7rem;
  font-weight: 800;
}

.calendar-palette__head strong {
  color: var(--text, #1f2937);
  font-size: 0.88rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.calendar-palette__grid {
  display: grid;
  gap: 4px;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  grid-template-rows: auto repeat(6, minmax(0, 1fr));
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.calendar-palette__weekday {
  color: var(--text-soft, #6b7280);
  font-size: 0.65rem;
  font-weight: 800;
  line-height: 1;
  text-align: center;
}

.calendar-palette__day {
  background: var(--calendar-day-bg, #f8fafc);
  border: 1px solid var(--calendar-day-border, #e5e7eb);
  display: grid;
  min-height: 0;
  overflow: hidden;
  padding: 4px;
  position: relative;
}

.calendar-palette__day.is-muted {
  background: var(--field-bg-muted, #f3f4f6);
  color: var(--text-muted, #9ca3af);
}

.calendar-palette__number {
  color: var(--text, #374151);
  font-size: 0.68rem;
  font-weight: 800;
  line-height: 1;
}

.calendar-palette__count {
  align-self: end;
  background: var(--field-bg, #ffffff);
  border: 1px solid var(--field-border, #d1d5db);
  color: var(--text, #111827);
  font-size: 0.62rem;
  font-weight: 800;
  justify-self: end;
  line-height: 1;
  min-width: 14px;
  max-width: 100%;
  overflow: hidden;
  padding: 1px 3px;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.calendar-palette__marker {
  bottom: 3px;
  height: 3px;
  left: 4px;
  max-width: calc(100% - 8px);
  position: absolute;
  width: 14px;
}

.calendar-palette__day.is-positive .calendar-palette__marker {
  background: #059669;
}

.calendar-palette__day.is-negative .calendar-palette__marker {
  background: #dc2626;
}

.calendar-palette__day.is-neutral .calendar-palette__marker {
  background: #6b7280;
}

.calendar-palette--1x2 {
  gap: 5px;
}

.calendar-palette--1x2 .calendar-palette__head {
  display: grid;
  gap: 2px;
}

.calendar-palette--1x2 .calendar-palette__grid {
  gap: 3px;
}

.calendar-palette--1x2 .calendar-palette__weekday {
  font-size: 0.56rem;
}

.calendar-palette--1x2 .calendar-palette__day {
  padding: 2px;
}

.calendar-palette--1x2 .calendar-palette__number {
  font-size: 0.56rem;
}

.calendar-palette--1x2 .calendar-palette__count {
  display: none;
}

.calendar-palette--1x2 .calendar-palette__marker {
  bottom: 2px;
  height: 2px;
  left: 2px;
  width: 9px;
}

:global(:root[data-theme='toss']) .calendar-palette__head span {
  color: #b9c5ff;
}

:global(:root[data-theme='toss']) .calendar-palette__head strong,
:global(:root[data-theme='toss']) .calendar-palette__count {
  color: var(--text, #f3f7ff);
}

:global(:root[data-theme='toss']) .calendar-palette__weekday,
:global(:root[data-theme='toss']) .calendar-palette__day.is-muted {
  color: var(--text-soft, #aeb8cb);
}

:global(:root[data-theme='toss']) .calendar-palette__day {
  background: var(--calendar-day-bg, rgba(27, 33, 44, 0.88));
  border-color: var(--calendar-day-border, rgba(78, 95, 125, 0.36));
}

:global(:root[data-theme='toss']) .calendar-palette__day.is-muted {
  background: var(--field-bg-muted, rgba(18, 23, 31, 0.86));
}

:global(:root[data-theme='toss']) .calendar-palette__number {
  color: var(--text, #f3f7ff);
}

:global(:root[data-theme='toss']) .calendar-palette__count {
  background: var(--field-bg, rgba(18, 23, 31, 0.96));
  border-color: var(--field-border, rgba(78, 95, 125, 0.46));
}

:global(:root[data-theme='toss']) .calendar-palette__day.is-positive .calendar-palette__marker {
  background: #5ee6a8;
}

:global(:root[data-theme='toss']) .calendar-palette__day.is-negative .calendar-palette__marker {
  background: #ff9b9b;
}

:global(:root[data-theme='toss']) .calendar-palette__day.is-neutral .calendar-palette__marker {
  background: #b9c5ff;
}
</style>
