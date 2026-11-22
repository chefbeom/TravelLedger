<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ledgerEntryCapabilities, splitPartIntents } from '../lib/ledgerEntryCapabilities'
import {
  adjustmentsForEntry, ledgerEntryStatusLabel, rowContainsEntryId,
  splitAdjustmentLabel, splitDisplayedAmount, splitExpectedCount, summarizeLedgerAdjustments,
} from '../lib/ledgerSheetGroups'
import LedgerManageDialog from './LedgerManageDialog.vue'

const props = defineProps({
  focusEntryId: { type: [String, Number], default: null },
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
  categoryGroups: { type: Array, default: () => [] },
  paymentMethods: { type: Array, default: () => [] },
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
  'ledger-changed',
])

const managedRow = ref(null)
const managedSplit = ref(null)
const managedInitialStep = ref('menu')
const sheetRoot = ref(null)
const sheetTitle = ref(null)
let focusedEntryKey = ''
watch(() => [props.focusEntryId, props.entries], async ([id, rows]) => {
  if (!id || !rows.some(row => rowContainsEntryId(row, id))) return
  const key = String(id)
  if (focusedEntryKey === key) return
  await nextTick()
  const target = sheetRoot.value?.querySelector('[data-calendar-focused-entry="true"]')
  if (target) { focusedEntryKey = key; target.scrollIntoView({ block: 'nearest' }); target.focus({ preventScroll: true }) }
}, { immediate: true })
let managerTrigger = null
const activeSplitKey = ref('')
const splitIntent = ref('view')
const splitCloseButton = ref(null)
let splitTrigger = null
const activeSplit = computed(() => props.entries.find(row => row.key === activeSplitKey.value && row.isSplit) || null)
const splitParts = computed(() => [...(activeSplit.value?.parts || [])].sort((a, b) => (a.splitPaymentIndex || 0) - (b.splitPaymentIndex || 0)))

watch(activeSplit, row => { if (!row) activeSplitKey.value = '' })

async function openSplit(row, event, intent = 'view') {
  splitTrigger = event.currentTarget
  splitIntent.value = intent
  activeSplitKey.value = row.key
  await nextTick()
  splitCloseButton.value?.focus()
}
function closeSplit(restoreFocus = true) {
  activeSplitKey.value = ''
  if (restoreFocus && splitTrigger?.isConnected) nextTick(() => splitTrigger?.focus())
}
function selectableParts(row) { return row.parts.filter(entry => ledgerEntryCapabilities(entry).bulkSelectable) }
const SPLIT_INTENT_LABELS = { refund: '환불', cancel: '결제 취소', uncancel: '결제 취소 취소', unrefund: '환불 취소', delete: '삭제' }
function partIntentAvailable(part, intent) {
  return splitPartIntents(part, adjustmentsForEntry(activeSplit.value, part.id)).includes(intent)
}
function selectedPartCount(row) { return selectableParts(row).filter(entry => props.selection.isSelected(entry)).length }
function toggleSplit(row) {
  const eligible = selectableParts(row)
  const remove = eligible.length > 0 && eligible.every(entry => props.selection.isSelected(entry))
  eligible.forEach(entry => { if (props.selection.isSelected(entry) !== !remove) props.selection.toggleItem(entry) })
}
function splitCount(row) {
  const expected = splitExpectedCount(row.parts)
  return expected > row.parts.length ? `${row.parts.length}/${expected}개 표시` : `${row.parts.length}개 수단`
}
function splitTitle(row) {
  const titles = new Set(row.parts.map(entry => entry.title || '제목 없음'))
  return titles.size > 1 ? `${row.parts[0].title || '제목 없음'} 외` : (row.parts[0].title || '제목 없음')
}
function splitCategory(row) {
  const labels = new Set(row.parts.map(entry => [entry.categoryGroupName, entry.categoryDetailName].filter(Boolean).join(' / ') || '-'))
  return labels.size === 1 ? [...labels][0] : '수단별로 다름'
}
function splitMemo(row) {
  const values = new Set(row.parts.map(entry => entry.visibleMemo || ''))
  return values.size === 1 ? ([...values][0] || '-') : '수단별로 다름'
}
function commonPartValue(row, read) {
  const values = new Set(row.parts.map(entry => read(entry) || ''))
  return values.size === 1 ? [...values][0] : null
}
function categoryFor(entry) { return [entry.categoryGroupName, entry.categoryDetailName].filter(Boolean).join(' / ') }
function splitTime(row) {
  const value = commonPartValue(row, entry => props.formatTime(entry.entryTime))
  return value === null ? '수단별로 다름' : value
}
function splitTypes(row) {
  const types = new Set(row.parts.map(entry => entry.entryType))
  return types.size > 1 ? '혼합' : row.parts[0]?.entryType === 'INCOME' ? '수입' : '지출'
}

// One summary per visible row, computed once instead of in every template expression.
const rowViews = computed(() => props.entries.map(row => ({
  row,
  summary: row.kind === 'entry' ? summarizeLedgerAdjustments(row.parts[0], row.adjustments) : null,
  caps: row.kind === 'entry' ? ledgerEntryCapabilities(row.parts[0], row.adjustments) : null,
})))
function shortDate(value) {
  const match = /^\d{4}-(\d{2})-(\d{2})/.exec(String(value || ''))
  return match ? `${Number(match[1])}월 ${Number(match[2])}일` : ''
}
function clock(value) { return value ? String(value).slice(0, 5) : '' }
function historyLine(origin, items) {
  if (!items.length) return origin.hasAdjustmentHistory ? '다른 날짜에 처리 기록이 있습니다' : ''
  return `처리 이력 ${items.length}건 · ${items.map(item => item.sameDateAsOrigin ? item.label : `${item.label} ${shortDate(item.entryDate)}`).join(' · ')}`
}
function adjustmentRowDetail(entry) {
  const base = `원거래 #${entry.adjustmentOriginId} · 다른 날짜의 거래`
  return entry.adjustmentType === 'REFUND'
    ? `${base} · 기준 ${money(entry.adjustmentPrincipal)} · 차감 ${money(entry.adjustmentDeduction)} · 추가 ${money(entry.adjustmentExtra)}`
    : base
}
function adjustmentRowNote(entry) {
  if (entry.financiallyExcluded) return '합계 제외'
  return entry.adjustmentType === 'REFUND' ? '환불 입금 · 원거래 다른 날짜' : ''
}
function partLabel(part) { return summarizeLedgerAdjustments(part, adjustmentsForEntry(activeSplit.value, part.id)).label }
function partAdjustments(part) { return summarizeLedgerAdjustments(part, adjustmentsForEntry(activeSplit.value, part.id)).items }

async function openManager(row, event, initialStep = 'menu') {
  managerTrigger = event?.currentTarget || null
  managedSplit.value = null
  managedInitialStep.value = initialStep
  managedRow.value = row
}
async function openSplitManager(row, event) {
  managerTrigger = event?.currentTarget || null
  managedSplit.value = row
  managedInitialStep.value = 'menu'
  managedRow.value = row
}
function openPartManager(part, event, initialStep = 'menu') {
  openManager({ key: `entry:${part.id}`, kind: 'entry', isSplit: false, parts: [part], adjustments: adjustmentsForEntry(activeSplit.value, part.id) }, event, initialStep)
}
async function chooseSplitAction(intent) {
  const row = managedSplit.value
  closeManager(false)
  splitIntent.value = intent
  activeSplitKey.value = row.key
  await nextTick()
  splitCloseButton.value?.focus()
}
function closeManager(restoreFocus = true) {
  managedRow.value = null
  managedSplit.value = null
  managedInitialStep.value = 'menu'
  if (restoreFocus) {
    nextTick(() => {
      if (managerTrigger?.isConnected) managerTrigger.focus()
      else sheetTitle.value?.focus()
    })
  }
}
function managerAction(action, payload) {
  closeManager(false)
  closeSplit(false)
  emit(action, payload)
}
function manageDelete(payload) {
  managerAction('delete-entry', payload)
  nextTick(() => sheetTitle.value?.focus())
}
function shareSplitPart(entry) { closeSplit(false); emit('share-entry', entry) }

function money(value) {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 2 }).format(Number(value || 0))
}

function updatePage(offset) {
  const next = Math.min(Math.max(props.page + offset, 0), Math.max(props.pageCount - 1, 0))
  emit('update:page', next)
}
</script>

<template>
  <section ref="sheetRoot" :class="['panel', 'household-sheet-panel', { 'household-sheet-panel--modal': modal }]">
    <div class="panel__header household-sheet-panel__header">
      <div>
        <nav class="household-sheet-date-nav" aria-label="거래 날짜 이동" data-no-drag="true">
          <button type="button" class="button button--ghost household-sheet-date-nav__button" aria-label="이전 날" title="이전 날" :disabled="!canMoveToPreviousDay" @click="emit('move-day', -1)">‹</button>
          <h2 ref="sheetTitle" tabindex="-1" aria-live="polite" aria-atomic="true">{{ selectedDateLabel }} 거래 시트</h2>
          <button type="button" class="button button--ghost household-sheet-date-nav__button" aria-label="다음 날" title="다음 날" :disabled="!canMoveToNextDay" @click="emit('move-day', 1)">›</button>
        </nav>
        <p>거래의 '관리'에서 수정·결제 취소·환불·삭제를 할 수 있습니다.</p>
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
          <template v-for="{ row, summary, caps } in rowViews" :key="row.key">
          <tr v-if="row.isSplit" class="split-sheet-row" :data-calendar-focused-entry="rowContainsEntryId(row, focusEntryId)" tabindex="-1">
            <td class="sheet-table__select"><input class="sheet-table__checkbox" type="checkbox"
              :aria-label="`${splitTitle(row)} 분할 거래 선택`" :disabled="!selectableParts(row).length"
              :checked="selectableParts(row).length > 0 && selectedPartCount(row) === selectableParts(row).length"
              :indeterminate.prop="selectedPartCount(row) > 0 && selectedPartCount(row) < selectableParts(row).length"
              @change="toggleSplit(row)" /></td>
            <td>{{ splitTime(row) }}</td>
            <td><span :class="['chip', splitTypes(row) === '혼합' ? 'chip--neutral' : splitTypes(row) === '수입' ? 'chip--income' : 'chip--expense']">{{ splitTypes(row) }}</span></td>
            <td class="sheet-table__title"><div class="adjustment-title-line"><span class="split-sheet-row__clip">{{ splitTitle(row) }}</span><span class="adjustment-status">분할 결제 · {{ splitCount(row) }}</span><span v-if="splitAdjustmentLabel(row)" class="adjustment-status">{{ splitAdjustmentLabel(row) }}</span></div></td>
            <td class="sheet-table__category">{{ splitCategory(row) }}</td>
            <td>{{ splitCount(row) }}</td>
            <td :class="['sheet-table__amount', splitTypes(row) === '혼합' ? '' : splitTypes(row) === '수입' ? 'is-income' : 'is-expense']"><span class="sheet-table__amount-stack"><span class="sheet-table__amount-main">{{ money(splitDisplayedAmount(row.parts)) }}</span><small class="sheet-table__amount-sub">표시된 수단 금액 합계</small></span></td>
            <td v-if="hasMemoColumn" class="sheet-table__memo split-sheet-row__memo"><span class="split-sheet-row__clip">{{ splitMemo(row) }}</span></td>
            <td class="sheet-table__actions">
              <div class="sheet-table__actions-inner split-sheet-actions">
                <button type="button" class="button button--ghost" aria-haspopup="dialog" @click="openSplitManager(row, $event)">관리</button>
                <button v-if="row.parts.some(part => !part.financiallyExcluded && !part.adjustmentType)" type="button" class="button button--ghost" @click="openSplit(row, $event, 'share')">공유</button>
                <button type="button" class="button button--ghost split-sheet-actions__view" @click="openSplit(row, $event)"><span>분할 결제</span> <span>보기</span></button>
              </div>
            </td>
          </tr>
          <tr v-else v-for="entry in row.parts" :key="entry.id" :data-calendar-focused-entry="rowContainsEntryId(row, focusEntryId)" tabindex="-1">
            <td class="sheet-table__select">
              <input
                class="sheet-table__checkbox"
                type="checkbox"
                :aria-label="`${entry.title || '제목 없음'} 선택`"
                :title="caps && !caps.bulkSelectable || entry.adjustmentType ? '처리 이력이 있어 일괄 작업에서 제외됩니다' : null"
                :disabled="!(caps?.bulkSelectable)"
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
                <div class="adjustment-title-line"><span>{{ entry.title }}</span><span v-if="row.kind === 'adjustment' ? ledgerEntryStatusLabel(entry) : summary.label" class="adjustment-status">{{ row.kind === 'adjustment' ? ledgerEntryStatusLabel(entry) : summary.label }}</span><span v-if="entry.splitPaymentGroupId" class="adjustment-status">분할 {{ entry.splitPaymentIndex }}/{{ entry.splitPaymentCount }}</span><span v-if="entry.installmentMonths" class="adjustment-status">{{ entry.installmentMonths }}개월 {{ entry.installmentInterestType === 'FREE' ? '무이자 ' : entry.installmentInterestType === 'INTEREST' ? '유이자 ' : '' }}할부</span></div>
                <small v-if="entry.splitPaymentGroupId" class="adjustment-detail">묶음 {{ entry.splitPaymentGroupId.slice(0, 8) }} · 수단별 개별 거래</small>
                <small v-if="row.kind === 'adjustment'" class="adjustment-detail">{{ adjustmentRowDetail(entry) }}</small>
                <small v-else-if="historyLine(entry, summary.items)" class="adjustment-detail">{{ historyLine(entry, summary.items) }}</small>
              </div>
            </td>
            <td class="sheet-table__category">
              {{ entry.categoryGroupName }}
              <template v-if="entry.categoryDetailName"> / {{ entry.categoryDetailName }}</template>
            </td>
            <td>{{ formatPaymentMethod(entry) }}</td>
            <td :class="['sheet-table__amount', entry.entryType === 'INCOME' ? 'is-income' : 'is-expense']">
              <span class="sheet-table__amount-stack">
                <span :class="['sheet-table__amount-main', { 'is-struck': entry.financiallyExcluded }]">{{ formatAmountMain(entry) }}</span>
                <small v-if="formatAmountSub(entry)" :class="['sheet-table__amount-sub', { 'is-struck': entry.financiallyExcluded }]">{{ formatAmountSub(entry) }}</small>
                <template v-if="row.kind === 'adjustment'">
                  <small v-if="adjustmentRowNote(entry)" class="sheet-table__amount-sub">{{ adjustmentRowNote(entry) }}</small>
                </template>
                <template v-else>
                  <small v-if="entry.financiallyExcluded" class="sheet-table__amount-sub">{{ entry.cancelledAt ? '결제 취소 · 합계 제외' : '합계 제외' }}</small>
                  <small v-if="summary.refundedPrincipal > 0" class="sheet-table__amount-sub">환불 기준 {{ money(summary.refundedPrincipal) }}</small>
                  <small v-if="summary.activeRefundReceived > 0" class="sheet-table__amount-sub">환불 입금 +{{ money(summary.activeRefundReceived) }}</small>
                </template>
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
                <button type="button" class="button button--ghost" aria-haspopup="dialog" @click="openManager(row, $event)">관리</button>
                <button v-if="caps ? caps.canShare : (!entry.financiallyExcluded && !entry.adjustmentType)" type="button" class="button button--ghost" @click="emit('share-entry', entry)">공유</button>
              </div>
            </td>
          </tr>
          </template>
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

    <Teleport to="body">
      <div v-if="activeSplit" class="split-detail-overlay" role="dialog" aria-modal="true" aria-labelledby="split-detail-title" @click.self="closeSplit()" @keydown.esc.stop="closeSplit()">
        <section class="split-detail-dialog panel">
          <header class="split-detail-header">
            <div><h2 id="split-detail-title">분할 결제 상세</h2><p>{{ splitTitle(activeSplit) }} · {{ splitCount(activeSplit) }}</p></div>
            <button ref="splitCloseButton" type="button" class="button button--ghost" data-modal-close @click="closeSplit()">닫기</button>
          </header>
          <p class="split-detail-total">표시된 수단 금액 합계 <strong>{{ money(splitDisplayedAmount(activeSplit.parts)) }}</strong></p>
          <p v-if="splitIntent === 'edit'" class="split-detail-note" role="status">수정하거나 처리할 결제수단의 관리 버튼을 선택하세요. 각 수단은 별도로 변경됩니다.</p>
          <p v-else-if="splitIntent === 'share'" class="split-detail-note" role="status">공유할 결제수단의 공유 버튼을 선택하세요. 각 수단은 별도로 공유됩니다.</p>
          <p v-else-if="splitIntent in SPLIT_INTENT_LABELS" class="split-detail-note" role="status">처리할 결제수단을 선택하세요. 선택한 수단에만 적용됩니다.</p>
          <p class="split-detail-note">각 결제수단은 별도 거래입니다. 합계는 표시된 원거래 금액이며 환불·취소를 차감한 금액이 아닙니다.</p>
          <p v-if="splitExpectedCount(activeSplit.parts) > activeSplit.parts.length" class="split-detail-note">현재 날짜와 필터에 해당하는 수단만 표시합니다. 다른 수단은 날짜 변경, 필터 또는 삭제로 보이지 않을 수 있습니다.</p>
          <dl class="split-detail-fields split-detail-common">
            <div v-if="commonPartValue(activeSplit, part => part.title) !== null"><dt>제목</dt><dd>{{ commonPartValue(activeSplit, part => part.title) || '-' }}</dd></div>
            <div v-if="commonPartValue(activeSplit, categoryFor) !== null"><dt>카테고리</dt><dd>{{ commonPartValue(activeSplit, categoryFor) || '-' }}</dd></div>
            <div v-if="commonPartValue(activeSplit, part => part.visibleMemo) !== null"><dt>메모</dt><dd>{{ commonPartValue(activeSplit, part => part.visibleMemo) || '-' }}</dd></div>
          </dl>
          <div class="split-detail-parts">
            <article v-for="part in splitParts" :key="part.id" class="split-detail-part">
              <div class="split-detail-part__head"><strong>결제 {{ part.splitPaymentIndex || '-' }}/{{ part.splitPaymentCount || splitParts.length }} · {{ formatPaymentMethod(part) }}</strong><strong>{{ formatAmountMain(part) }}</strong></div>
              <small v-if="formatAmountSub(part)">{{ formatAmountSub(part) }}</small>
              <span v-if="partLabel(part)" class="split-detail-status">{{ partLabel(part) }}</span>
              <span v-if="part.installmentMonths">{{ part.installmentMonths }}개월 {{ part.installmentInterestType === 'FREE' ? '무이자 ' : part.installmentInterestType === 'INTEREST' ? '유이자 ' : '' }}할부</span>
              <span v-if="Number(part.refundedPrincipal || 0) > 0">환불 기준금액 {{ money(part.refundedPrincipal) }}</span>
              <dl class="split-detail-fields">
                <div v-if="commonPartValue(activeSplit, item => item.title) === null"><dt>제목</dt><dd>{{ part.title || '-' }}</dd></div>
                <div><dt>시간 · 구분</dt><dd>{{ formatTime(part.entryTime) }} · {{ part.entryType === 'INCOME' ? '수입' : '지출' }}</dd></div>
                <div v-if="commonPartValue(activeSplit, categoryFor) === null"><dt>카테고리</dt><dd>{{ categoryFor(part) || '-' }}</dd></div>
                <div v-if="commonPartValue(activeSplit, item => item.visibleMemo) === null"><dt>메모</dt><dd>{{ part.visibleMemo || '-' }}</dd></div>
              </dl>
              <ul v-if="partAdjustments(part).length" class="split-detail-adjustments" aria-label="이 수단의 처리 기록">
                <li v-for="item in partAdjustments(part)" :key="item.id">{{ item.label }} · {{ item.entryDate }}<template v-if="item.entryTime"> {{ clock(item.entryTime) }}</template> · {{ money(item.amount) }}</li>
              </ul>
              <div class="split-detail-actions">
                <template v-if="splitIntent in SPLIT_INTENT_LABELS">
                  <button v-if="partIntentAvailable(part, splitIntent)" type="button" :class="['button', splitIntent === 'delete' ? 'button--danger' : 'button--ghost']" @click="openPartManager(part, $event, splitIntent)">이 수단 {{ SPLIT_INTENT_LABELS[splitIntent] }}</button>
                </template>
                <template v-else><button type="button" class="button button--ghost" aria-haspopup="dialog" @click="openPartManager(part, $event)">관리</button>
                <button v-if="!part.financiallyExcluded && !part.adjustmentType" type="button" class="button button--ghost" @click="shareSplitPart(part)">공유</button></template>
              </div>
            </article>
          </div>
        </section>
      </div>
      <LedgerManageDialog
        v-if="managedRow"
        :key="managedRow.key"
        :row="managedRow"
        :split="Boolean(managedSplit)"
        :initial-step="managedInitialStep"
        :category-groups="categoryGroups"
        :payment-methods="paymentMethods"
        :format-time="formatTime"
        :format-payment-method="formatPaymentMethod"
        :format-amount-main="formatAmountMain"
        :format-amount-sub="formatAmountSub"
        @close="closeManager($event !== false)"
        @edit-entry="managerAction('edit-entry', $event)"
        @delete-entry="manageDelete"
        @choose-split-action="chooseSplitAction"
        @ledger-changed="emit('ledger-changed', $event)"
      />
    </Teleport>
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
:global(:root[data-layout-mode='mobile'] .household-sheet-panel .adjustment-title-content) { max-width: 75%; text-align: right; }
:global(:root[data-layout-mode='mobile'] .household-sheet-panel .adjustment-title-line) { justify-content: flex-end; }
.sheet-table__actions-inner.household-sheet-actions--adjustment { grid-template-columns: repeat(2, minmax(0, 1fr)) !important; }
:global(:root[data-layout-mode='mobile'] .household-sheet-panel .sheet-table__actions-inner.household-sheet-actions--adjustment) { grid-template-columns: repeat(2, minmax(0, 1fr)) !important; }
.sheet-table__amount-main.is-struck, .sheet-table__amount-sub.is-struck { text-decoration: line-through; }
.split-detail-adjustments { display: grid; gap: .2rem; margin: 0; padding-left: 1.1rem; font-size: .85rem; }
.split-sheet-row__memo { max-width: 12rem; overflow-wrap: anywhere; }
.split-sheet-row__clip { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; overflow-wrap: anywhere; }
.sheet-table__actions-inner.split-sheet-actions { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1.4fr) !important; }
:global(:root[data-layout-mode='mobile'] .household-sheet-panel .sheet-table__actions-inner.split-sheet-actions) { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1.4fr) !important; }
.split-sheet-actions .split-sheet-actions__view { white-space: normal !important; padding-inline: 2px !important; font-size: .75rem; line-height: 1.2; }
.split-sheet-actions__view span { display: block; white-space: nowrap; }
.split-detail-overlay { position: fixed; inset: 0; z-index: 2650; display: grid; place-items: center; padding: 1rem; background: rgb(0 0 0 / 65%); }
.split-detail-dialog { box-sizing: border-box; width: min(100%, 44rem); max-height: calc(100dvh - 2rem); overflow-y: auto; background: var(--panel-bg); color: var(--text); text-align: left; }
.split-detail-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; }
.split-detail-header h2 { margin: 0; }
.split-detail-header p, .split-detail-note { margin: .4rem 0; line-height: 1.45; }
.split-detail-header .button { flex: 0 0 auto; width: auto; min-width: 2.75rem; min-height: 2.75rem; }
.split-detail-total { display: flex; flex-wrap: wrap; justify-content: space-between; gap: .5rem; margin: .8rem 0; }
.split-detail-note { font-size: .85rem; }
.split-detail-common { margin: .8rem 0; }
.split-detail-parts { display: grid; gap: .65rem; margin-top: 1rem; }
.split-detail-part { display: grid; gap: .4rem; min-width: 0; padding: .8rem; border: 1px solid var(--border); overflow-wrap: anywhere; }
.split-detail-part__head { display: flex; justify-content: space-between; flex-wrap: wrap; gap: .5rem; }
.split-detail-status { width: fit-content; padding: .1rem .35rem; border: 1px solid currentColor; font-size: .8rem; }
.split-detail-fields { display: grid; gap: .25rem; margin: .2rem 0; }
.split-detail-fields div { display: grid; grid-template-columns: 5rem minmax(0, 1fr); gap: .5rem; }
.split-detail-fields dt { font-weight: 700; }
.split-detail-fields dd { min-width: 0; margin: 0; white-space: pre-wrap; }
.split-detail-actions { display: flex; flex-wrap: wrap; gap: .5rem; }
.split-detail-actions .button { width: auto; min-height: 2.75rem; }
</style>
