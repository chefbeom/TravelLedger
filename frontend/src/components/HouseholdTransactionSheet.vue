<script setup>
const props = defineProps({
  selectedDateLabel: { type: String, required: true },
  countLabel: { type: String, required: true },
  entries: { type: Array, default: () => [] },
  totalEntryCount: { type: Number, default: 0 },
  hasMemoColumn: { type: Boolean, default: false },
  hasForeignEntries: { type: Boolean, default: false },
  amountMode: { type: String, default: 'KRW' },
  entryFilter: { type: String, default: 'ALL' },
  sort: { type: String, default: 'ASC' },
  selection: { type: Object, required: true },
  page: { type: Number, default: 0 },
  pageCount: { type: Number, default: 1 },
  formatTime: { type: Function, required: true },
  formatPaymentMethod: { type: Function, required: true },
  formatAmountMain: { type: Function, required: true },
  formatAmountSub: { type: Function, required: true },
  modal: { type: Boolean, default: false },
  canMoveToPreviousDay: { type: Boolean, default: true },
  canMoveToNextDay: { type: Boolean, default: true },
})

const emit = defineEmits([
  'update:amount-mode',
  'update:entry-filter',
  'update:sort',
  'update:page',
  'edit-entry',
  'share-entry',
  'delete-entry',
  'open-settings',
  'close',
  'move-day',
  'adjust-entry',
  'revert-adjustment',
  'show-adjustments',
])

function statusLabel(entry) {
  if (entry.adjustmentVoidedAt) return '처리 되돌림'
  if (entry.adjustmentType === 'PAYMENT_CANCELLATION') return '결제 취소 기록'
  if (entry.adjustmentType === 'REFUND') return '환불 입금'
  if (entry.cancelledAt) return '결제 취소됨'
  if (Number(entry.refundedPrincipal || 0) > 0) {
    return Number(entry.refundedPrincipal) >= Number(entry.amount) ? '환불 완료' : '부분 환불'
  }
  return entry.hasAdjustmentHistory ? '처리 이력 있음' : ''
}
function money(value) {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 2 }).format(Number(value || 0))
}
function canRefund(entry) {
  return entry.entryType === 'EXPENSE' && !entry.adjustmentType && !entry.financiallyExcluded
    && Number(entry.amount || 0) > Number(entry.refundedPrincipal || 0)
}
function canCancel(entry) {
  return entry.entryType === 'EXPENSE' && !entry.adjustmentType && !entry.financiallyExcluded
    && !Number(entry.refundedPrincipal || 0) && !entry.travelRecordId
}

function updatePage(offset) {
  const next = Math.min(Math.max(props.page + offset, 0), Math.max(props.pageCount - 1, 0))
  emit('update:page', next)
}
</script>

<template>
  <section :class="['panel', 'household-sheet-panel', { 'household-sheet-panel--modal': modal }]">
    <div class="panel__header household-sheet-panel__header">
      <div>
        <nav class="household-sheet-date-nav" aria-label="거래 날짜 이동" data-no-drag="true">
          <button type="button" class="button button--ghost household-sheet-date-nav__button" aria-label="이전 날" title="이전 날" :disabled="!canMoveToPreviousDay" @click="emit('move-day', -1)">‹</button>
          <h2 aria-live="polite" aria-atomic="true">{{ selectedDateLabel }} 거래 시트</h2>
          <button type="button" class="button button--ghost household-sheet-date-nav__button" aria-label="다음 날" title="다음 날" :disabled="!canMoveToNextDay" @click="emit('move-day', 1)">›</button>
        </nav>
        <p>선택한 날짜의 거래를 바로 수정하거나 삭제할 수 있습니다.</p>
      </div>
      <div class="household-sheet-header">
        <span class="panel__badge">{{ countLabel }}</span>
        <button type="button" class="button button--secondary" @click="emit('open-settings')">거래 시트 설정</button>
        <button v-if="modal" type="button" class="button button--ghost" data-modal-close @click="emit('close')">닫기</button>
        <div v-if="hasForeignEntries" class="scope-toggle">
          <button type="button" class="button" :class="{ 'button--primary': amountMode === 'KRW' }" @click="emit('update:amount-mode', 'KRW')">원화</button>
          <button type="button" class="button" :class="{ 'button--primary': amountMode === 'FOREIGN' }" @click="emit('update:amount-mode', 'FOREIGN')">외화</button>
        </div>
        <label class="household-sheet-filter">
          <span>거래 구분</span>
          <select aria-label="거래 구분" :value="entryFilter" @change="emit('update:entry-filter', $event.target.value)">
            <option value="ALL">전체</option><option value="INCOME">수입</option><option value="EXPENSE">지출</option>
          </select>
        </label>
        <label class="household-sheet-filter">
          <span>시간 정렬</span>
          <select aria-label="시간 정렬" :value="sort" @change="emit('update:sort', $event.target.value)">
            <option value="ASC">오름차순</option><option value="DESC">내림차순</option>
          </select>
        </label>
      </div>
    </div>

    <div class="sheet-table-wrap household-sheet-table-wrap" :class="{ 'household-sheet-table-wrap--scroll': totalEntryCount > 5 }">
      <table class="sheet-table household-sheet-table">
        <colgroup>
          <col class="household-sheet-col household-sheet-col--select" />
          <col class="household-sheet-col household-sheet-col--time" />
          <col class="household-sheet-col household-sheet-col--type" />
          <col class="household-sheet-col household-sheet-col--title" />
          <col class="household-sheet-col household-sheet-col--category" />
          <col class="household-sheet-col household-sheet-col--payment" />
          <col class="household-sheet-col household-sheet-col--amount" />
          <col v-if="hasMemoColumn" class="household-sheet-col household-sheet-col--memo" />
          <col class="household-sheet-col household-sheet-col--actions" />
        </colgroup>
        <thead>
          <tr>
            <th class="sheet-table__select">
              <input
                class="sheet-table__checkbox"
                type="checkbox"
                :checked="selection.allVisibleSelected"
                :indeterminate.prop="selection.someVisibleSelected"
                @change="selection.toggleAllVisible()"
              />
            </th>
            <th>시간</th>
            <th>구분</th>
            <th>제목</th>
            <th>카테고리</th>
            <th>결제수단</th>
            <th>금액</th>
            <th v-if="hasMemoColumn">메모</th>
            <th>작업</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="entry in entries" :key="entry.id">
            <td class="sheet-table__select">
              <input
                class="sheet-table__checkbox"
                type="checkbox"
                :disabled="entry.hasAdjustmentHistory || !!entry.adjustmentType"
                :checked="selection.isSelected(entry)"
                @change="selection.toggleItem(entry)"
              />
            </td>
            <td>{{ formatTime(entry.entryTime) }}</td>
            <td>
              <span :class="['chip', entry.entryType === 'INCOME' ? 'chip--income' : 'chip--expense']">
                {{ entry.entryType === 'INCOME' ? '수입' : '지출' }}
              </span>
            </td>
            <td class="sheet-table__title">
              <div class="adjustment-title-content">
                <div class="adjustment-title-line"><span>{{ entry.title }}</span><span v-if="statusLabel(entry)" class="adjustment-status">{{ statusLabel(entry) }}</span></div>
                <small v-if="entry.adjustmentType === 'REFUND'" class="adjustment-detail">
                  원거래 #{{ entry.adjustmentOriginId }} · 기준 {{ money(entry.adjustmentPrincipal) }} · 차감 {{ money(entry.adjustmentDeduction) }} · 추가 {{ money(entry.adjustmentExtra) }}
                </small>
                <small v-else-if="entry.adjustmentType" class="adjustment-detail">원거래 #{{ entry.adjustmentOriginId }}</small>
                <small v-else-if="Number(entry.refundedPrincipal || 0) > 0" class="adjustment-detail">환불 기준금액 {{ money(entry.refundedPrincipal) }}</small>
              </div>
            </td>
            <td class="sheet-table__category">
              {{ entry.categoryGroupName }}
              <template v-if="entry.categoryDetailName"> / {{ entry.categoryDetailName }}</template>
            </td>
            <td>{{ formatPaymentMethod(entry) }}</td>
            <td :class="['sheet-table__amount', entry.entryType === 'INCOME' ? 'is-income' : 'is-expense']">
              <span class="sheet-table__amount-stack">
                <span class="sheet-table__amount-main">{{ formatAmountMain(entry) }}</span>
                <small v-if="formatAmountSub(entry)" class="sheet-table__amount-sub">{{ formatAmountSub(entry) }}</small>
              </span>
            </td>
            <td v-if="hasMemoColumn" class="sheet-table__memo">
              <details v-if="(entry.visibleMemo || '').length > 80" class="household-sheet-memo">
                <summary><span>{{ entry.visibleMemo }}</span><small>메모 펼치기 / 접기</small></summary>
                <p>{{ entry.visibleMemo }}</p>
              </details>
              <span v-else>{{ entry.visibleMemo || '-' }}</span>
            </td>
            <td class="sheet-table__actions">
              <div class="sheet-table__actions-inner household-sheet-actions--adjustment">
                <button v-if="!entry.hasAdjustmentHistory && !entry.adjustmentType" type="button" class="button button--ghost" @click="emit('edit-entry', entry)">수정</button>
                <button v-if="!entry.financiallyExcluded && !entry.adjustmentType" type="button" class="button button--ghost" @click="emit('share-entry', entry)">공유</button>
                <button v-if="canRefund(entry)" type="button" class="button button--ghost" @click="emit('adjust-entry', { entry, mode: 'REFUND' })">환불</button>
                <button v-if="canCancel(entry)" type="button" class="button button--ghost" @click="emit('adjust-entry', { entry, mode: 'PAYMENT_CANCELLATION' })">결제 취소</button>
                <button v-if="entry.hasAdjustmentHistory && !entry.adjustmentType" type="button" class="button button--ghost" @click="emit('show-adjustments', entry)">처리 내역</button>
                <button v-if="entry.adjustmentType && !entry.adjustmentVoidedAt" type="button" class="button button--ghost" @click="emit('revert-adjustment', entry)">되돌리기</button>
                <button v-if="!entry.hasAdjustmentHistory && !entry.adjustmentType" type="button" class="button button--danger" @click="emit('delete-entry', entry)">삭제</button>
              </div>
              <small v-if="entry.travelRecordId && !entry.adjustmentType && !entry.financiallyExcluded" class="adjustment-detail">여행 연결 거래의 결제 취소는 지원하지 않습니다.</small>
            </td>
          </tr>
          <tr v-if="!totalEntryCount">
            <td :colspan="hasMemoColumn ? 9 : 8" class="sheet-table__empty">선택한 날짜에는 거래가 없습니다.</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="totalEntryCount > 1000" class="panel__actions">
      <button class="button button--ghost" type="button" :disabled="page <= 0" @click="updatePage(-1)">이전</button>
      <span>{{ page + 1 }} / {{ pageCount }}</span>
      <button class="button button--ghost" type="button" :disabled="page + 1 >= pageCount" @click="updatePage(1)">다음</button>
    </div>
  </section>
</template>

<style scoped>
.household-sheet-date-nav {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-width: 0;
}

.household-sheet-date-nav h2 {
  margin: 0;
  white-space: nowrap;
}

.household-sheet-date-nav__button {
  flex: 0 0 2.75rem;
  min-width: 2.75rem;
  min-height: 2.75rem;
  padding: 0;
  font-size: 1.5rem;
  line-height: 1;
}

.adjustment-title-content { min-width: 0; overflow-wrap: anywhere; }
.adjustment-title-line { display: flex; flex-wrap: wrap; align-items: center; gap: .35rem; }
.adjustment-status { display: inline-block; padding: .1rem .35rem; border: 1px solid currentColor; border-radius: .25rem; font-size: .75rem; white-space: nowrap; }
.adjustment-detail { display: block; margin-top: .3rem; line-height: 1.4; }
:global(:root[data-layout-mode='mobile']) .adjustment-title-content { max-width: 75%; text-align: right; }
:global(:root[data-layout-mode='mobile']) .adjustment-title-line { justify-content: flex-end; }
.sheet-table__actions-inner.household-sheet-actions--adjustment { grid-template-columns: repeat(2, minmax(0, 1fr)) !important; }
:global(:root[data-layout-mode='mobile']) .sheet-table__actions-inner.household-sheet-actions--adjustment { grid-template-columns: repeat(2, minmax(0, 1fr)) !important; }
</style>
