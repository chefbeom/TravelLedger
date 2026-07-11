<script setup>
import { computed } from 'vue'

const props = defineProps({
  title: {
    type: String,
    required: true,
  },
  subtitle: {
    type: String,
    default: '',
  },
  items: {
    type: Array,
    default: () => [],
  },
  formatValue: {
    type: Function,
    required: true,
  },
  emptyText: {
    type: String,
    default: '표시할 데이터가 없습니다.',
  },
  dense: {
    type: Boolean,
    default: false,
  },
  valueFormatter: {
    type: Function,
    default: null,
  },
})

const maxValue = computed(() => {
  if (!props.items.length) {
    return 1
  }

  return Math.max(...props.items.map((item) => Number(item.value ?? 0)), 1)
})

const maxBarFillPercent = 88

function getHeight(item) {
  const value = Number(item.value ?? 0)
  if (value <= 0) {
    return '0%'
  }

  // The track height changes by density and viewport. Keep the tallest bar
  // below the top edge using the track's own height rather than a fixed pixel value.
  return `${Math.max(6, Math.min(maxBarFillPercent, (value / maxValue.value) * maxBarFillPercent))}%`
}

function formatDisplayValue(item) {
  if (typeof props.valueFormatter === 'function') {
    return props.valueFormatter(item.value, item)
  }
  return props.formatValue(item.value, item)
}
</script>

<template>
  <section class="panel chart-card">
    <div class="panel__header">
      <div>
        <h3>{{ title }}</h3>
        <p v-if="subtitle">{{ subtitle }}</p>
      </div>
    </div>

    <div v-if="items.length" :class="['bar-chart', { 'bar-chart--dense': dense }]">
      <article v-for="(item, index) in items" :key="`${item.label}-${index}`" class="bar-chart__item">
        <div class="bar-chart__value" :title="formatValue(item.value, item)">{{ formatDisplayValue(item) }}</div>
        <div class="bar-chart__track">
          <span
            class="bar-chart__bar"
            :style="{
              height: getHeight(item),
              background: item.color
                ? `linear-gradient(180deg, ${item.color}, color-mix(in srgb, ${item.color} 70%, white))`
                : undefined,
            }"
          />
        </div>
        <div class="bar-chart__label">{{ item.label }}</div>
        <small v-if="item.caption" class="bar-chart__caption">{{ item.caption }}</small>
      </article>
    </div>

    <p v-else class="panel__empty">{{ emptyText }}</p>
  </section>
</template>
