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

const weekdayLabels = ['일', '월', '화', '수', '목', '금', '토']
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
function formatCompactAmount(value) {
  const amount = Math.abs(Number(value ?? 0))
  if (!amount) return ''

  const units = [
    { value: 1_000_000_000, suffix: 'B' },
    { value: 1_000_000, suffix: 'M' },
    { value: 1_000, suffix: 'k' },
  ]
  const unit = units.find((item) => amount >= item.value)
  if (!unit) {
    return String(Math.round(amount))
  }

  const compact = amount / unit.value
  const fractionDigits = compact >= 10 ? 0 : 1
  return `${compact.toFixed(fractionDigits).replace(/\.0$/, '')}${unit.suffix}`
}

function expenseCompact(day) {
  return formatCompactAmount(day?.summary?.expense)
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
        <span v-if="expenseCompact(day)" class="calendar-palette__expense">{{ expenseCompact(day) }}</span>
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
  color: var(--household-dash-teal, #006960);
  font-size: 0.7rem;
  font-weight: 800;
}

.calendar-palette__head strong {
  color: var(--household-dash-ink, #1f2937);
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
  color: var(--household-dash-muted, #6b7280);
  font-size: 0.65rem;
  font-weight: 800;
  line-height: 1;
  text-align: center;
}

.calendar-palette__day {
  background: var(--household-dash-tile, #f8fafc);
  border: 1px solid var(--household-dash-line, #e5e7eb);
  border-radius: 8px;
  display: grid;
  min-height: 0;
  overflow: hidden;
  padding: 4px;
  position: relative;
}

.calendar-palette__day.is-muted {
  background: var(--household-dash-control, #f3f4f6);
  color: var(--text-muted, #9ca3af);
}

.calendar-palette__number {
  color: var(--household-dash-ink, #374151);
  font-size: 0.68rem;
  font-weight: 800;
  line-height: 1;
}

.calendar-palette__expense {
  align-self: end;
  background: var(--household-dash-card, #ffffff);
  border: 1px solid var(--household-dash-line, #d1d5db);
  border-radius: 999px;
  color: var(--household-dash-ink, #111827);
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
  border-radius: 999px;
  height: 3px;
  left: 4px;
  max-width: calc(100% - 8px);
  position: absolute;
  width: 14px;
}

.calendar-palette__day.is-positive .calendar-palette__marker {
  background: var(--household-dash-teal, #006960);
}

.calendar-palette__day.is-negative .calendar-palette__marker {
  background: var(--household-dash-coral, #ff765f);
}

.calendar-palette__day.is-neutral .calendar-palette__marker {
  background: var(--household-dash-mint, #67ded1);
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

.calendar-palette--1x2 .calendar-palette__expense {
  display: none;
}

.calendar-palette--1x2 .calendar-palette__marker {
  bottom: 2px;
  height: 2px;
  left: 2px;
  width: 9px;
}

:global(html[data-theme='toss'] .calendar-palette__head span) {
  color: var(--household-dash-teal, #78c9c0);
}

:global(html[data-theme='toss'] .calendar-palette__head strong),
:global(html[data-theme='toss'] .calendar-palette__expense) {
  color: var(--household-dash-ink, #edf3f8);
}

:global(html[data-theme='toss'] .calendar-palette__weekday),
:global(html[data-theme='toss'] .calendar-palette__day.is-muted) {
  color: var(--household-dash-muted, #a3b0bf);
}

:global(html[data-theme='toss'] .calendar-palette__day) {
  background: var(--household-dash-tile, rgba(34, 42, 55, 0.8));
  border-color: var(--household-dash-line, rgba(91, 107, 129, 0.32));
}

:global(html[data-theme='toss'] .calendar-palette__day.is-muted) {
  background: var(--household-dash-control, rgba(33, 41, 54, 0.94));
}

:global(html[data-theme='toss'] .calendar-palette__number) {
  color: var(--household-dash-ink, #edf3f8);
}

:global(html[data-theme='toss'] .calendar-palette__expense) {
  background: var(--household-dash-card, rgba(24, 31, 42, 0.97));
  border-color: var(--household-dash-line, rgba(91, 107, 129, 0.32));
}

:global(html[data-theme='toss'] .calendar-palette__day.is-positive .calendar-palette__marker) {
  background: var(--household-dash-positive, #b5d98a);
}

:global(html[data-theme='toss'] .calendar-palette__day.is-negative .calendar-palette__marker) {
  background: var(--household-dash-negative, #e39a91);
}

:global(html[data-theme='toss'] .calendar-palette__day.is-neutral .calendar-palette__marker) {
  background: var(--household-dash-teal, #78c9c0);
}
</style>
