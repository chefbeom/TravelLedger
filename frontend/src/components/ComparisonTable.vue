<script setup>
import { formatCurrency } from '../lib/format'

defineProps({
  rows: {
    type: Array,
    default: () => [],
  },
})
</script>

<template>
  <div class="comparison-table">
    <table>
      <thead>
        <tr>
          <th>기간</th>
          <th>수입</th>
          <th>지출</th>
          <th>잔액</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="`${row.label}-${row.startDate}`">
          <td>{{ row.label }}</td>
          <td class="is-income">{{ formatCurrency(row.income) }}</td>
          <td class="is-expense">{{ formatCurrency(row.expense) }}</td>
          <td :class="Number(row.balance) >= 0 ? 'is-income' : 'is-expense'">
            {{ formatCurrency(row.balance) }}
          </td>
        </tr>
        <tr v-if="!rows.length">
          <td colspan="4" class="sheet-table__empty">비교할 데이터가 없습니다.</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
