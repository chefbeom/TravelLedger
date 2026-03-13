<script setup>
import { computed } from 'vue'
import { formatCurrency } from '../lib/format'

const props = defineProps({
  title: {
    type: String,
    required: true,
  },
  items: {
    type: Array,
    default: () => [],
  },
})

const maxValue = computed(() => {
  if (!props.items.length) {
    return 1
  }
  return Math.max(...props.items.map((item) => Number(item.totalAmount ?? 0)), 1)
})

function getLabel(item) {
  if (item.groupName) {
    return item.detailName ? `${item.groupName} / ${item.detailName}` : item.groupName
  }
  return `${item.paymentMethodName} (${item.kind})`
}

function getRatio(item) {
  return `${(Number(item.totalAmount ?? 0) / maxValue.value) * 100}%`
}
</script>

<template>
  <section class="breakdown-list">
    <div class="panel__header">
      <div>
        <h3>{{ title }}</h3>
      </div>
    </div>

    <div v-if="items.length" class="breakdown-list__items">
      <article v-for="item in items" :key="getLabel(item)" class="breakdown-list__item">
        <div class="breakdown-list__row">
          <strong>{{ getLabel(item) }}</strong>
          <span>{{ formatCurrency(item.totalAmount) }}</span>
        </div>
        <div class="breakdown-list__bar">
          <span :style="{ width: getRatio(item) }" />
        </div>
        <small>{{ item.entryCount }}건</small>
      </article>
    </div>

    <p v-else class="panel__empty">표시할 통계가 없습니다.</p>
  </section>
</template>
