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
})

const total = computed(() => props.items.reduce((sum, item) => sum + Number(item.value ?? 0), 0))

const arcs = computed(() => {
  if (!props.items.length || total.value <= 0) {
    return []
  }

  const circumference = 2 * Math.PI * 42
  let offset = 0

  return props.items.map((item) => {
    const value = Number(item.value ?? 0)
    const length = (value / total.value) * circumference
    const arc = {
      ...item,
      circumference,
      strokeDasharray: `${length} ${circumference - length}`,
      strokeDashoffset: -offset,
    }
    offset += length
    return arc
  })
})
</script>

<template>
  <section class="panel chart-card">
    <div class="panel__header">
      <div>
        <h3>{{ title }}</h3>
        <p v-if="subtitle">{{ subtitle }}</p>
      </div>
    </div>

    <div v-if="arcs.length" class="donut-chart">
      <div class="donut-chart__visual">
        <svg viewBox="0 0 120 120" class="donut-chart__svg" aria-hidden="true">
          <circle cx="60" cy="60" r="42" class="donut-chart__bg" />
          <circle
            v-for="(arc, index) in arcs"
            :key="`${arc.label}-${index}`"
            cx="60"
            cy="60"
            r="42"
            class="donut-chart__arc"
            :style="{
              stroke: arc.color,
              strokeDasharray: arc.strokeDasharray,
              strokeDashoffset: arc.strokeDashoffset,
            }"
          />
        </svg>
        <div class="donut-chart__center">
          <strong>{{ formatValue(total) }}</strong>
          <span>총합</span>
        </div>
      </div>

      <div class="donut-chart__legend">
        <article v-for="(item, index) in arcs" :key="`${item.label}-${index}`" class="donut-chart__legend-item">
          <span class="donut-chart__swatch" :style="{ background: item.color }" />
          <div>
            <strong>{{ item.label }}</strong>
            <p>{{ formatValue(item.value, item) }}</p>
            <small v-if="item.caption">{{ item.caption }}</small>
          </div>
        </article>
      </div>
    </div>

    <p v-else class="panel__empty">{{ emptyText }}</p>
  </section>
</template>
