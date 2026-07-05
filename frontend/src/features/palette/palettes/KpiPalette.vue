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
      <strong v-if="!data.hideTitle">{{ data.title || '가계부' }}</strong>
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
  gap: 10px;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
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
  color: var(--household-dash-teal, #006960);
  font-size: 0.76rem;
  font-weight: 800;
}

.kpi-palette__head strong {
  color: var(--household-dash-ink, #1f2937);
  font-size: 0.98rem;
  line-height: 1.2;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kpi-palette__metric {
  align-self: center;
  background: var(--household-dash-tile, #f6faf8);
  border: 1px solid var(--household-dash-line, rgba(0, 105, 96, 0.14));
  border-radius: 14px;
  display: grid;
  gap: 4px;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  padding: 12px;
}

.kpi-palette__metric strong {
  color: var(--household-dash-ink, #111827);
  display: block;
  font-variant-numeric: tabular-nums;
  font-size: clamp(1.24rem, 2.2vw, 2.08rem);
  letter-spacing: 0;
  line-height: 1.16;
  min-width: 0;
  overflow: hidden;
  overflow-wrap: anywhere;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kpi-palette__metric small {
  color: var(--household-dash-muted, #6b7280);
  font-size: 0.82rem;
  line-height: 1.25;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kpi-palette__metric--positive strong,
.is-positive {
  color: var(--household-dash-positive, #006960);
}

.kpi-palette__metric--negative strong,
.is-negative {
  color: var(--household-dash-negative, #c7513f);
}

.is-neutral {
  color: var(--household-dash-muted, #374151);
}

.kpi-palette__rows {
  display: grid;
  gap: 5px;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding-right: 2px;
  scrollbar-gutter: stable;
}

.kpi-palette__row {
  align-items: center;
  border-top: 1px solid var(--household-dash-line, #eef0f4);
  display: flex;
  gap: 8px;
  justify-content: space-between;
  min-width: 0;
  padding-top: 5px;
}

.kpi-palette__row span,
.kpi-palette__bar-label span {
  color: var(--household-dash-muted, #6b7280);
  font-size: 0.78rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kpi-palette__row strong,
.kpi-palette__bar-label strong {
  font-variant-numeric: tabular-nums;
  font-size: 0.84rem;
  line-height: 1.2;
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
  background: rgba(0, 105, 96, 0.1);
  border-radius: 999px;
  height: 6px;
  overflow: hidden;
}

.kpi-palette__bar-track span {
  background: var(--household-dash-teal, #006960);
  border-radius: inherit;
  display: block;
  height: 100%;
}

.kpi-palette__bar-track .is-positive {
  background: var(--household-dash-teal, #006960);
}

.kpi-palette__bar-track .is-negative {
  background: var(--household-dash-coral, #ff765f);
}

.kpi-palette__metric--positive {
  background: var(--household-dash-lime-soft, #f1ffbe);
  border-color: rgba(0, 105, 96, 0.12);
}

.kpi-palette__metric--negative {
  background: var(--household-dash-coral-soft, #ffe2dc);
  border-color: rgba(199, 81, 63, 0.14);
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

:global(html[data-theme='toss'] .kpi-palette__head span) {
  color: var(--household-dash-teal, #78c9c0);
}

:global(html[data-theme='toss'] .kpi-palette__head strong),
:global(html[data-theme='toss'] .kpi-palette__metric strong),
:global(html[data-theme='toss'] .is-neutral) {
  color: var(--household-dash-ink, #edf3f8);
}

:global(html[data-theme='toss'] .kpi-palette__metric small),
:global(html[data-theme='toss'] .kpi-palette__row span),
:global(html[data-theme='toss'] .kpi-palette__bar-label span) {
  color: var(--household-dash-muted, #a3b0bf);
}

:global(html[data-theme='toss'] .kpi-palette__row) {
  border-top-color: var(--household-dash-line, rgba(91, 107, 129, 0.32));
}

:global(html[data-theme='toss'] .kpi-palette__bar-track) {
  background: var(--household-dash-track, rgba(96, 112, 136, 0.24));
}

:global(html[data-theme='toss'] .kpi-palette__metric--positive),
:global(html[data-theme='toss'] .kpi-palette__metric--negative) {
  background: var(--household-dash-tile, rgba(34, 42, 55, 0.8));
  border-color: var(--household-dash-line, rgba(91, 107, 129, 0.32));
}

:global(html[data-theme='toss'] .kpi-palette__metric--positive strong),
:global(html[data-theme='toss'] .is-positive) {
  color: var(--household-dash-positive, #b5d98a);
}

:global(html[data-theme='toss'] .kpi-palette__metric--negative strong),
:global(html[data-theme='toss'] .is-negative) {
  color: var(--household-dash-negative, #e39a91);
}
</style>
