<script setup>
import { adjustmentItemLabel, adjustmentsForEntry, ledgerEntryStatusLabel, summarizeLedgerAdjustments } from '../../lib/ledgerSheetGroups.js'
import CalendarIcon from './CalendarIcon.vue'
import InfoTip from './InfoTip.vue'
import RowMenu from './RowMenu.vue'

const props = defineProps({
  item: { type: Object, required: true },
  hideAmounts: { type: Boolean, default: false },
  busy: { type: Boolean, default: false },
  currency: { type: Function, required: true },
})
const emit = defineEmits(['open'])
const ICON = { ledger: 'ledger', places: 'places', travel: 'travel', routes: 'routes', events: 'events' }
const adjustmentCount = () => props.item.data.adjustments?.length || 0
const detailSummary = () => {
  const base = props.item.data.isSplit ? `거래 1건 · 결제수단 ${props.item.data.paymentMethodCount}개 상세` : '거래 상세'
  return adjustmentCount() ? `${base} · 처리 ${adjustmentCount()}건` : base
}
const partStatus = part => part.adjustmentType || props.item.data.kind === 'adjustment'
  ? ledgerEntryStatusLabel(part)
  : summarizeLedgerAdjustments(part, adjustmentsForEntry(props.item.data, part.id)).label
const clock = value => value ? `${String(value).slice(0, 5)} · ` : ''
</script>

<template>
  <li class="integrated-calendar__entry" :data-layer="item.layer" :data-shared="item.shared ? 'true' : 'false'" :data-key="item.key">
    <div class="integrated-calendar__entry-row">
      <button type="button" class="integrated-calendar__entry-main" :aria-label="`${item.primary.label}: ${item.title}`" :aria-busy="busy" :disabled="busy" @click="emit('open', item.primary.action)">
        <span class="integrated-calendar__entry-time">{{ item.timeLabel }}</span>
        <span class="integrated-calendar__entry-stripe" aria-hidden="true"><CalendarIcon :name="item.shared ? 'shared' : ICON[item.layer]" :size="14" /></span>
        <span class="integrated-calendar__entry-body">
          <span class="integrated-calendar__entry-title">{{ item.title }}</span>
          <span v-if="item.subtitle" class="integrated-calendar__entry-sub">{{ item.subtitle }}</span>
          <span v-if="item.memo" class="integrated-calendar__entry-memo">{{ item.memo }}</span>
          <span v-if="item.badges.length" class="integrated-calendar__entry-badges"><span v-for="badge in item.badges" :key="badge" class="integrated-calendar__badge">{{ badge }}</span></span>
        </span>
        <span v-if="item.amount" class="integrated-calendar__entry-amount" :data-tone="item.amount.tone" :data-struck="item.amount.struck ? 'true' : null">{{ hideAmounts ? '금액 숨김' : item.amount.text }}</span>
      </button>
      <InfoTip v-if="item.timeNote" :text="item.timeNote" />
      <RowMenu v-if="item.secondary.length" :label="item.title" :items="item.secondary" @select="emit('open', $event)" />
    </div>
    <a v-if="item.thumb?.href" class="integrated-calendar__entry-thumb" :href="item.thumb.href" target="_blank" rel="noopener noreferrer"><img :src="item.thumb.src" :alt="item.thumb.alt" width="40" height="40" loading="lazy"></a>
    <img v-else-if="item.thumb" class="integrated-calendar__entry-thumb" :src="item.thumb.src" :alt="item.thumb.alt" width="40" height="40" loading="lazy">
    <details v-if="item.kind === 'ledger'" class="integrated-calendar__entry-more">
      <summary>{{ detailSummary() }}</summary>
      <div v-for="part in item.data.parts" :key="part.id" class="integrated-calendar__entry-part">{{ part.entryTime ? `${String(part.entryTime).slice(0, 5)} · ` : '' }}{{ part.paymentMethodName }} · {{ currency(part.amount) }} <span v-if="partStatus(part)">· {{ partStatus(part) }} </span><span v-if="part.installmentMonths">· {{ part.installmentMonths }}개월 {{ part.installmentInterestType === 'FREE' ? '무이자' : part.installmentInterestType === 'INTEREST' ? '유이자' : '' }} 할부</span><span v-if="part.adjustmentType === 'REFUND'"> · 기준 {{ currency(part.adjustmentPrincipal) }} · 차감 {{ currency(part.adjustmentDeduction) }} · 추가 {{ currency(part.adjustmentExtra) }}</span><span v-if="part.foreignCurrencyCode && part.foreignAmount != null && !hideAmounts"> · {{ part.foreignCurrencyCode }} {{ part.foreignAmount }}</span><span v-if="part.financiallyExcluded"> · 합계 제외</span></div>
      <div v-for="adjustment in item.data.adjustments || []" :key="`adjustment-${adjustment.id}`" class="integrated-calendar__entry-part" data-adjustment="true">{{ clock(adjustment.entryTime) }}{{ adjustmentItemLabel(adjustment) }} · {{ currency(adjustment.amount) }}<span v-if="adjustment.adjustmentType === 'REFUND'"> · 기준 {{ currency(adjustment.adjustmentPrincipal) }} · 차감 {{ currency(adjustment.adjustmentDeduction) }} · 추가 {{ currency(adjustment.adjustmentExtra) }}</span><span v-if="adjustment.financiallyExcluded"> · 합계 제외</span></div>
    </details>
  </li>
</template>

<style scoped>
.integrated-calendar__entry { position: relative; display: grid; gap: .25rem; padding: 0; list-style: none; border: 1px solid var(--ic-line); border-left: 3px solid var(--ic-layer, var(--ic-line)); background: var(--ic-surface); overflow-wrap: anywhere; }
.integrated-calendar__entry[data-shared='true'] { border-style: dashed; border-left-style: solid; }
.integrated-calendar__entry-row { display: flex; align-items: stretch; min-width: 0; padding-right: .25rem; }
.integrated-calendar .integrated-calendar__entry-main { flex: 1 1 auto; display: grid; grid-template-columns: 4.75rem auto minmax(0, 1fr) auto; align-items: start; gap: .5rem; min-width: 0; min-height: 2.75rem; padding: .5rem .5rem .5rem .625rem; border-width: 0 !important; background: transparent !important; box-shadow: none !important; text-align: left; font: inherit; cursor: pointer; }
.integrated-calendar .integrated-calendar__entry-main > span, .integrated-calendar .integrated-calendar__entry-main span span { -webkit-text-fill-color: currentColor; }
.integrated-calendar .integrated-calendar__entry-main:hover, .integrated-calendar .integrated-calendar__entry-main:focus-visible { background: var(--ic-accent-soft) !important; }
.integrated-calendar__entry-time { white-space: nowrap; word-break: keep-all; color: var(--ic-text-soft); font-size: .8125rem; line-height: 1.4; font-variant-numeric: tabular-nums; }
.integrated-calendar__entry-stripe { display: inline-flex; padding-top: .125rem; color: var(--ic-layer, var(--ic-text-soft)); }
.integrated-calendar__entry-body { display: grid; gap: .125rem; min-width: 0; }
.integrated-calendar__entry-title { font-weight: 700; line-height: 1.35; }
.integrated-calendar__entry-sub { color: var(--ic-text-soft); font-size: .8125rem; }
.integrated-calendar__entry-memo { display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; font-size: .8125rem; }
.integrated-calendar__entry-badges { display: flex; flex-wrap: wrap; gap: .25rem; }
.integrated-calendar .integrated-calendar__badge { padding: 0 .375rem; border: 1px solid var(--ic-line); background: var(--ic-surface-muted); font-size: .75rem; line-height: 1.4; }
.integrated-calendar .integrated-calendar__entry-amount[data-struck='true'] { text-decoration: line-through; }
.integrated-calendar .integrated-calendar__entry-amount { font-weight: 700; font-size: .9375rem; white-space: nowrap; }
.integrated-calendar .integrated-calendar__entry-amount[data-tone='income'] { color: var(--ic-income) !important; -webkit-text-fill-color: var(--ic-income) !important; }
.integrated-calendar .integrated-calendar__entry-amount[data-tone='expense'] { color: var(--ic-expense) !important; -webkit-text-fill-color: var(--ic-expense) !important; }
.integrated-calendar__entry-row > :deep(.integrated-calendar__infotip) { align-self: center; margin-inline: .875rem .875rem; }
.integrated-calendar__entry-row > :deep(.integrated-calendar__rowmenu) { align-self: center; }
.integrated-calendar__entry-thumb { margin: 0 .625rem .5rem 5.875rem; width: 2.5rem; height: 2.5rem; object-fit: cover; }
.integrated-calendar__entry-thumb :deep(img), img.integrated-calendar__entry-thumb { width: 2.5rem; height: 2.5rem; object-fit: cover; display: block; }
.integrated-calendar__entry-more { margin: 0 .625rem .5rem 5.875rem; font-size: .8125rem; }
.integrated-calendar__entry-more summary { cursor: pointer; min-height: 2.75rem; display: flex; align-items: center; }
.integrated-calendar__entry-part { padding: .125rem 0; }
@media (max-width: 35rem) {
  .integrated-calendar .integrated-calendar__entry-main { grid-template-columns: 4.5rem auto minmax(0, 1fr); }
  .integrated-calendar .integrated-calendar__entry-amount { grid-column: 3; }
  .integrated-calendar__entry-thumb, .integrated-calendar__entry-more { margin-left: 5.75rem; }
}
</style>
