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

const rows = computed(() => props.data.rows ?? [])
const bars = computed(() => props.data.bars ?? [])
const isCompact = computed(() => props.config.size === '1x1' || props.config.size === '1x2')
const canShowBars = computed(() => ['3x2', '3x3'].includes(props.config.size))
</script>

<template>
  <div class="kpi-palette" :class="`kpi-palette--${config.size}`">
    <div class="kpi-palette__head">
      <span>{{ data.eyebrow || 'KPI' }}</span>
      <strong>{{ data.title || '가계부' }}</strong>
    </div>

    <div class="kpi-palette__metric" :class="`kpi-palette__metric--${data.tone || 'neutral'}`">
      <strong>{{ data.value || '0' }}</strong>
      <small>{{ data.meta || '-' }}</small>
    </div>

    <div v-if="!isCompact && rows.length" class="kpi-palette__rows">
      <div v-for="row in rows" :key="`${row.label}-${row.value}`" class="kpi-palette__row">
        <span>{{ row.label }}</span>
        <strong :class="`is-${row.tone || 'neutral'}`">{{ row.value }}</strong>
      </div>
    </div>

    <div v-if="canShowBars && bars.length" class="kpi-palette__bars">
      <div v-for="bar in bars" :key="bar.label" class="kpi-palette__bar">
        <div class="kpi-palette__bar-label">
          <span>{{ bar.label }}</span>
          <strong>{{ bar.value ? `${bar.percent}%` : '0%' }}</strong>
        </div>
        <div class="kpi-palette__bar-track">
          <span :class="`is-${bar.tone || 'neutral'}`" :style="{ width: `${Math.max(4, Math.min(bar.percent || 0, 100))}%` }"></span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kpi-palette {
  display: grid;
  gap: 8px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.kpi-palette__head {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.kpi-palette__head span {
  color: #6f42c1;
  font-size: 0.7rem;
  font-weight: 800;
}

.kpi-palette__head strong {
  color: #1f2937;
  font-size: 0.9rem;
  line-height: 1.2;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kpi-palette__metric {
  align-self: center;
  display: grid;
  gap: 4px;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}

.kpi-palette__metric strong {
  color: #111827;
  display: -webkit-box;
  font-size: clamp(1.15rem, 2.2vw, 2rem);
  line-height: 1.05;
  min-width: 0;
  overflow: hidden;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.kpi-palette__metric small {
  color: #6b7280;
  font-size: 0.75rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kpi-palette__metric--positive strong,
.is-positive {
  color: #047857;
}

.kpi-palette__metric--negative strong,
.is-negative {
  color: #b91c1c;
}

.is-neutral {
  color: #374151;
}

.kpi-palette__rows {
  display: grid;
  gap: 5px;
  min-height: 0;
  overflow: hidden;
}

.kpi-palette__row {
  align-items: center;
  border-top: 1px solid #eef0f4;
  display: flex;
  gap: 8px;
  justify-content: space-between;
  min-width: 0;
  padding-top: 5px;
}

.kpi-palette__row span,
.kpi-palette__bar-label span {
  color: #6b7280;
  font-size: 0.72rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kpi-palette__row strong,
.kpi-palette__bar-label strong {
  font-size: 0.78rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kpi-palette__bars {
  display: grid;
  gap: 7px;
  min-height: 0;
  overflow: hidden;
}

.kpi-palette__bar {
  display: grid;
  gap: 4px;
}

.kpi-palette__bar-label {
  align-items: center;
  display: flex;
  gap: 8px;
  justify-content: space-between;
  min-width: 0;
}

.kpi-palette__bar-track {
  background: #edf0f4;
  height: 6px;
  overflow: hidden;
}

.kpi-palette__bar-track span {
  background: #6b7280;
  display: block;
  height: 100%;
}

.kpi-palette__bar-track .is-positive {
  background: #059669;
}

.kpi-palette__bar-track .is-negative {
  background: #dc2626;
}

.kpi-palette--1x1 {
  gap: 6px;
}

.kpi-palette--1x1 .kpi-palette__head span,
.kpi-palette--1x1 .kpi-palette__metric small {
  font-size: 0.66rem;
}

.kpi-palette--1x1 .kpi-palette__head strong {
  font-size: 0.78rem;
}

.kpi-palette--1x1 .kpi-palette__metric strong {
  font-size: clamp(0.98rem, 1.6vw, 1.2rem);
}
</style>
