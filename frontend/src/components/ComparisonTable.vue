<script setup>
import { computed } from 'vue'
import { formatCurrency } from '../lib/format'

const props = defineProps({
  rows: {
    type: Array,
    default: () => [],
  },
})

const rowsWithChangeRate = computed(() =>
  props.rows.map((row, index) => {
    const previousExpense = Number(props.rows[index - 1]?.expense ?? 0)
    const currentExpense = Number(row.expense ?? 0)
    const changeRate = index === 0 || previousExpense === 0
      ? null
      : ((currentExpense - previousExpense) / previousExpense) * 100

    return {
      ...row,
      changeRate,
    }
  }),
)

function formatChangeRate(value) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '-'
  }

  const fixed = Math.abs(value) >= 100 ? value.toFixed(0) : value.toFixed(1)
  return `${value > 0 ? '+' : ''}${fixed}%`
}
</script>

<template>
  <div class="comparison-table">
    <table>
      <thead>
        <tr>
          <th>기간</th>
          <th>수입</th>
          <th>지출</th>
          <th>증감률</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rowsWithChangeRate" :key="`${row.label}-${row.startDate}`">
          <td>{{ row.label }}</td>
          <td class="is-income">{{ formatCurrency(row.income) }}</td>
          <td class="is-expense">{{ formatCurrency(row.expense) }}</td>
          <td :class="row.changeRate === null ? '' : (row.changeRate >= 0 ? 'is-income' : 'is-expense')">
            {{ formatChangeRate(row.changeRate) }}
          </td>
        </tr>
        <tr v-if="!rowsWithChangeRate.length">
          <td colspan="4" class="sheet-table__empty">비교할 데이터가 없습니다.</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
