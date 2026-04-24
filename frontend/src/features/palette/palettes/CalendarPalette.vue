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
  color: #1f2937;
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
}

.calendar-palette__weekday {
  color: #6b7280;
  font-size: 0.65rem;
  font-weight: 800;
  line-height: 1;
  text-align: center;
}

.calendar-palette__day {
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  display: grid;
  min-height: 0;
  overflow: hidden;
  padding: 4px;
  position: relative;
}

.calendar-palette__day.is-muted {
  background: #f3f4f6;
  color: #9ca3af;
}

.calendar-palette__number {
  color: #374151;
  font-size: 0.68rem;
  font-weight: 800;
  line-height: 1;
}

.calendar-palette__count {
  align-self: end;
  background: #ffffff;
  border: 1px solid #d1d5db;
  color: #111827;
  font-size: 0.62rem;
  font-weight: 800;
  justify-self: end;
  line-height: 1;
  min-width: 14px;
  padding: 1px 3px;
  text-align: center;
}

.calendar-palette__marker {
  bottom: 3px;
  height: 3px;
  left: 4px;
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
</style>
