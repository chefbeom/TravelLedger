<script setup>
import { formatCurrency } from '../lib/format'

defineProps({
  card: {
    type: Object,
    required: true,
  },
})
</script>

<template>
  <article class="summary-card">
    <div class="summary-card__label">{{ card.label }}</div>
    <div class="summary-card__balance">{{ formatCurrency(card.overview.balance) }}</div>
    <div class="summary-card__meta">
      <span>수입 {{ formatCurrency(card.overview.income) }}</span>
      <span>지출 {{ formatCurrency(card.overview.expense) }}</span>
    </div>
    <div class="summary-card__count">{{ card.overview.entryCount }}건</div>
  </article>
</template>
