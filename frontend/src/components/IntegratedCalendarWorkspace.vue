<script setup>
import { computed, nextTick, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { calendarLedgerDay, calendarMonthGrid, calendarWeekday, monthTargetDate, overlapsCalendarDate, shiftCalendarDate, splitCalendarWeeks } from '../lib/integratedCalendarCore.js'
import { layoutWeekBars } from '../lib/integratedCalendarBars.js'
import { useMediaQuery } from '../lib/useMediaQuery.js'
import { LAYERS, SOURCE_LABELS, collectMonthSpans, formatMonthDay, isLayerVisible, buildDaySummaries, buildDayTimeline, buildMonthSummary } from '../lib/integratedCalendarView.js'
import { useIntegratedCalendarSources } from '../lib/useIntegratedCalendarSources.js'
import CalendarToolbar from './integrated-calendar/CalendarToolbar.vue'
import MonthSummaryStrip from './integrated-calendar/MonthSummaryStrip.vue'
import CalendarIssuesBanner from './integrated-calendar/CalendarIssuesBanner.vue'
import SharedSourceOverlay from './integrated-calendar/SharedSourceOverlay.vue'
import BaseDialog from './integrated-calendar/BaseDialog.vue'
import DayPanel from './integrated-calendar/DayPanel.vue'
import EventDialog from './integrated-calendar/EventDialog.vue'
import ConfirmDialog from './integrated-calendar/ConfirmDialog.vue'
import MonthGrid from './integrated-calendar/MonthGrid.vue'
import CalendarSettingsDrawer from './integrated-calendar/CalendarSettingsDrawer.vue'
import { deleteCalendarEvent, saveCalendarEvent } from '../lib/api.js'

const emit = defineEmits(['navigate', 'open-travel-source', 'open-place-source', 'open-ledger-source'])
const now = new Date()
const today = ref(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`)
const year = ref(now.getFullYear()), month = ref(now.getMonth() + 1), selectedDate = ref(today.value)
const grid = computed(() => calendarMonthGrid(year.value, month.value))
const layers = reactive({ ledger: true, places: true, travel: true, routes: true, events: true })
const filters = reactive({ entryType: '', categoryId: '', paymentMethodId: '', collectionId: '', planId: '' })
const settingsOpen = ref(false)
const hideAmounts = ref(false), eventBusy = ref(false), eventError = ref('')
const eventDialog = ref(null), confirmDelete = ref(null)
const includeShared = ref(true)
const sharedSource = ref(null) // { kind: 'travel', share: { type, shareId }, detail }
const mapSource = ref(null) // { kind: 'map', grantId, focus }

const visible = source => isLayerVisible(layers, source)
const currency = value => hideAmounts.value ? '금액 숨김' : new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(Number(value) || 0)

const sources = useIntegratedCalendarSources({ grid, selectedDate, filters, includeShared, isVisible: visible })
const {
  monthSources, monthErrors, monthBusy, monthInitialLoading, monthRevalidating, daySources, dayErrors, dayBusy, dayInitialLoading, dayRevalidating, dayPending,
  sharedOptions, sharedSummaries, sharedDays, sharedErrors, sharedLoading, sharedBusy, sharedSourceBusy,
  mapOptions, mapSummaries, mapDays, mapErrors, mapLoading, mapBusy,
  categories, paymentMethods, collections, plans,
  undated, undatedError, undatedBusy, actionError, issues, retrying, retryFailed,
  refreshAll, refreshEvents, loadFilterOptions, loadUndated, changeUndatedPage, dispose,
} = sources
const sharedErrorText = computed(() => Object.values(sharedErrors).filter(Boolean).join(' '))

const dailyLedger = computed(() => calendarLedgerDay((daySources.ledger || []).filter(e => (!filters.entryType || e.entryType === filters.entryType) && (!filters.categoryId || String(e.categoryGroupId) === String(filters.categoryId)) && (!filters.paymentMethodId || String(e.paymentMethodId) === String(filters.paymentMethodId))), selectedDate.value))
const dayPeriods = computed(() => (monthSources.travel?.periods || []).filter(period => overlapsCalendarDate(period.startDate, period.endDate, selectedDate.value)))
const timeline = computed(() => buildDayTimeline({
  date: selectedDate.value, isVisible: visible, includeShared: includeShared.value, hideAmounts: hideAmounts.value,
  ledgerRows: visible('ledger') && daySources.ledger ? dailyLedger.value.rows : null, daySources, periods: dayPeriods.value,
  sharedSummaries: sharedSummaries.value, sharedDays: sharedDays.value, mapDays: mapDays.value, formatCurrency: currency,
}))
const monthSummary = computed(() => buildMonthSummary({ grid: grid.value, month: monthSources, isVisible: visible, includeShared: includeShared.value, shared: sharedSummaries.value }))
const layerState = computed(() => Object.fromEntries(LAYERS.map(layer => [layer, visible(layer)])))
const showCollections = computed(() => layers.places || layers.travel)
const showPlans = computed(() => layers.places || layers.travel || layers.routes)
// Only filters currently offered by the drawer are counted.
const activeFilterCount = computed(() => (layers.ledger ? [filters.entryType, filters.categoryId, filters.paymentMethodId].filter(Boolean).length : 0) + (showCollections.value && filters.collectionId ? 1 : 0) + (showPlans.value && filters.planId ? 1 : 0))
function toggleLayer(layer) {
  layers[layer] = !layers[layer]
  // A filter that belongs only to hidden layers must not keep narrowing the data silently.
  if (!layers.ledger) Object.assign(filters, { entryType: '', categoryId: '', paymentMethodId: '' })
  if (!showCollections.value) filters.collectionId = ''
  if (!showPlans.value) filters.planId = ''
}
function resetFilters() { Object.assign(filters, { entryType: '', categoryId: '', paymentMethodId: '', collectionId: '', planId: '' }) }
function openUndated(name, payload) { settingsOpen.value = false; emit(name, payload) }
const daySummaries = computed(() => buildDaySummaries({ days: grid.value.days, month: monthSources, sharedSummaries: sharedSummaries.value, mapSummaries: mapSummaries.value, isVisible: visible, includeShared: includeShared.value, formatCurrency: currency }))
const monthSpans = computed(() => collectMonthSpans({ month: monthSources, sharedSummaries: sharedSummaries.value, isVisible: visible, includeShared: includeShared.value }))
const miniCells = useMediaQuery('(max-width: 35rem)')
const compact = useMediaQuery('(max-width: 60rem)')
const sheetOpen = ref(false)
watch(compact, value => { if (!value) sheetOpen.value = false })
const peekText = computed(() => {
  const expense = dayTotals.value?.expense
  return `${formatMonthDay(selectedDate.value)} · 항목 ${timeline.value.length}개${expense ? ` · 지출 ${currency(expense)}` : ''}`
})
const maxLanes = computed(() => miniCells.value ? 2 : 3)
const weeks = computed(() => splitCalendarWeeks(grid.value.days).map(days => {
  const dates = days.map(day => day.date)
  const layout = layoutWeekBars(dates, monthSpans.value, maxLanes.value)
  return { key: dates[0], ...layout, days: days.map((day, col) => ({ ...day, weekday: calendarWeekday(day.date), summary: daySummaries.value.get(day.date), hiddenBars: layout.hiddenByCol[col] })) }
}))
async function onSelectDate(date, { fromMore = false } = {}) {
  chooseDate(date)
  if (compact.value) { sheetOpen.value = true; return }
  if (fromMore) { await nextTick(); document.getElementById('integrated-calendar-day-title')?.focus() }
}

async function openSharedMapSource(share, item) {
  if (!sources.canOpenSharedMap(share.id)) return
  mapSource.value = { kind: 'map', grantId: share.id, focus: { token: Date.now(), placeId: item.placeId, recordId: item.id, sourceKind: item.sourceKind, date: selectedDate.value } }
}
async function openSharedTravelSource(share) {
  sharedSource.value = null
  const detail = await sources.openSharedTravelSource(share)
  if (!detail) return
  sharedSource.value = { kind: 'travel', share: { type: share.type, shareId: share.shareId }, detail }
}
// An open original is only closed when its permission disappears, never by a refresh.
watch(sharedSummaries, list => {
  const current = sharedSource.value
  if (!current || list.some(item => item.type === current.share.type && item.shareId === current.share.shareId)) return
  sharedSource.value = null
  if (includeShared.value) actionError.value = '공유 권한이 변경되어 원본을 닫았습니다.'
})
watch(mapSummaries, list => {
  const current = mapSource.value
  if (!current || list.some(item => item.id === current.grantId)) return
  mapSource.value = null
  if (includeShared.value) actionError.value = '공유 권한이 변경되어 원본을 닫았습니다.'
})
const noLayers = computed(() => !LAYERS.some(layer => layers[layer]))
const dayTotals = computed(() => visible('ledger') && daySources.ledger ? { income: dailyLedger.value.income, expense: dailyLedger.value.expense } : null)
const pendingDaySources = computed(() => Object.keys(daySources).filter(source => visible(source) && daySources[source] == null && dayPending[source] && !dayErrors[source]).map(source => SOURCE_LABELS[source]))
function shiftDay(step) { chooseDate(shiftCalendarDate(selectedDate.value, step)) }
function handleOpen(action) {
  const { type, payload } = action
  if (type === 'ledger') emit('open-ledger-source', payload)
  else if (type === 'place') emit('open-place-source', payload)
  else if (type === 'travel') emit('open-travel-source', payload)
  else if (type === 'event-edit') openEvent(payload)
  else if (type === 'event-delete') confirmDelete.value = payload
  else if (type === 'shared-travel') openSharedTravelSource({ type: payload.type, shareId: payload.shareId })
  else if (type === 'shared-map') openSharedMapSource({ id: payload.shareId }, payload.item)
}
function setMonth(nextYear, nextMonth) {
  if (nextYear < 1900 || nextYear > 9998) return
  year.value = nextYear; month.value = nextMonth
  selectedDate.value = monthTargetDate(year.value, month.value, today.value)
}
function moveMonth(offset) {
  const date = new Date(year.value, month.value - 1 + offset, 1)
  setMonth(date.getFullYear(), date.getMonth() + 1)
}
function pickMonth({ year: nextYear, month: nextMonth }) { setMonth(nextYear, nextMonth) }
function chooseDate(date) {
  selectedDate.value = date
  if (!date.startsWith(`${year.value}-${String(month.value).padStart(2, '0')}`)) { year.value = Number(date.slice(0, 4)); month.value = Number(date.slice(5, 7)) }
}
function goToday() { const current = new Date(); year.value = current.getFullYear(); month.value = current.getMonth() + 1; today.value = `${year.value}-${String(month.value).padStart(2, '0')}-${String(current.getDate()).padStart(2, '0')}`; selectedDate.value = today.value }
function toggleSettings() { settingsOpen.value = !settingsOpen.value }
function openEvent(event = null) {
  eventError.value = ''
  eventDialog.value = { mode: event ? 'edit' : 'create', event }
}
const eventInitial = computed(() => {
  const event = eventDialog.value?.event
  return event ? { ...event, startTime: event.startTime?.slice(0, 5) || '', endTime: event.endTime?.slice(0, 5) || '', memo: event.memo || '' }
    : { id: null, version: null, title: '', startDate: selectedDate.value, endDate: selectedDate.value, allDay: true, startTime: '', endTime: '', memo: '' }
})
async function submitEvent(draft) {
  eventError.value = ''
  const endDate = draft.endDate || draft.startDate
  eventBusy.value = true
  try {
    await saveCalendarEvent(draft.id, { title: draft.title.trim(), startDate: draft.startDate, endDate, allDay: draft.allDay,
      startTime: draft.allDay ? null : draft.startTime || null, endTime: draft.allDay ? null : draft.endTime || null, memo: draft.memo, version: draft.version })
    eventDialog.value = null; await refreshEvents()
  } catch (error) { eventError.value = error.status === 409 ? '다른 화면에서 일정이 변경되었습니다. 다시 불러온 뒤 수정해 주세요.' : error.message }
  finally { eventBusy.value = false }
}
async function removeEvent() {
  const event = confirmDelete.value
  eventBusy.value = true
  try {
    await deleteCalendarEvent(event.id, event.version)
    confirmDelete.value = null; eventDialog.value = null
    await refreshEvents()
  } catch (error) {
    confirmDelete.value = null; eventDialog.value = null
    actionError.value = error.status === 409 ? '다른 화면에서 일정이 변경되었습니다. 다시 불러와 주세요.' : error.message
  } finally { eventBusy.value = false }
  await nextTick()
  document.getElementById('integrated-calendar-day-title')?.focus()
}
let lastVisibilityRefresh = 0
function refreshWhenVisible() {
  if (document.visibilityState !== 'visible' || Date.now() - lastVisibilityRefresh < 1000) return
  lastVisibilityRefresh = Date.now()
  const current = new Date()
  today.value = `${current.getFullYear()}-${String(current.getMonth() + 1).padStart(2, '0')}-${String(current.getDate()).padStart(2, '0')}`
  refreshAll()
}
onMounted(() => {
  window.addEventListener('focus', refreshWhenVisible)
  document.addEventListener('visibilitychange', refreshWhenVisible)
  refreshAll(); loadFilterOptions()
})
onBeforeUnmount(() => {
  dispose()
  window.removeEventListener('focus', refreshWhenVisible)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>

<template>
  <section class="integrated-calendar" aria-label="통합 달력">
    <CalendarToolbar :year="year" :month="month" :today-iso="today" :revalidating="monthBusy || dayBusy" :active-filter-count="activeFilterCount" :layers="layers" :include-shared="includeShared" @toggle-layer="toggleLayer" @toggle-shared="includeShared = !includeShared" @move="moveMonth" @pick-month="pickMonth" @today="goToday" @refresh="refreshAll" @open-settings="toggleSettings" />
    <BaseDialog v-if="compact && sheetOpen" variant="sheet" class="integrated-calendar__sheet" labelledby="integrated-calendar-day-title" @close="sheetOpen = false">
      <div class="integrated-calendar__sheet-body">
        <DayPanel mode="sheet" :date="selectedDate" :today-iso="today" :timeline="timeline" :day-totals="dayTotals" :initial-loading="dayInitialLoading" :revalidating="dayRevalidating" :pending-sources="pendingDaySources" :hide-amounts="hideAmounts"
          :no-layers="noLayers" :shared-busy="sharedSourceBusy" :currency="currency" @open="handleOpen" @add-event="openEvent()" @prev-day="shiftDay(-1)" @next-day="shiftDay(1)" @close="sheetOpen = false" />
      </div>
    </BaseDialog>
    <CalendarSettingsDrawer v-if="settingsOpen" :filters="filters" :layers="layers" :categories="categories" :payment-methods="paymentMethods" :collections="collections" :plans="plans"
      :shared-options="sharedOptions" :shared-loading="sharedLoading" :shared-busy="sharedBusy" :shared-action-error="sharedErrors.action" :map-options="mapOptions" :map-loading="mapLoading" :map-busy="mapBusy" :map-action-error="mapErrors.action"
      :undated="undated" :undated-error="undatedError" :undated-busy="undatedBusy" :toggle-shared="sources.toggleShared" :toggle-map="sources.toggleSharedMap" :add-map="sources.addSharedMap"
      @close="settingsOpen = false" @reset="resetFilters" @load-undated="loadUndated" @undated-page="changeUndatedPage" @open-place="openUndated('open-place-source', $event)" @open-travel="openUndated('open-travel-source', $event)" />
    <CalendarIssuesBanner :issues="issues" :retrying="retrying" @retry="retryFailed" />
    <MonthSummaryStrip :summary="monthSummary" :month-sources="monthSources" :month-errors="monthErrors" :layers="layerState" :include-shared="includeShared" :shared-loading="sharedLoading" :shared-failed="Boolean(sharedErrorText)" :hide-amounts="hideAmounts" :currency="currency" @toggle-hide="hideAmounts = !hideAmounts" />
    <div class="integrated-calendar__layout">
      <MonthGrid :weeks="weeks" :selected-date="selectedDate" :today-iso="today" :hide-amounts="hideAmounts" :initial-loading="monthInitialLoading" :revalidating="monthRevalidating" :mini="miniCells" @select="onSelectDate" />
      <aside v-if="!compact" class="integrated-calendar__details" :aria-busy="dayBusy">
        <DayPanel mode="panel" :date="selectedDate" :today-iso="today" :timeline="timeline" :day-totals="dayTotals" :initial-loading="dayInitialLoading" :revalidating="dayRevalidating" :pending-sources="pendingDaySources" :hide-amounts="hideAmounts"
          :no-layers="noLayers" :shared-busy="sharedSourceBusy" :currency="currency" @open="handleOpen" @add-event="openEvent()" @prev-day="shiftDay(-1)" @next-day="shiftDay(1)" />
      </aside>
    </div>
    <button v-if="compact" type="button" class="integrated-calendar__peek" aria-haspopup="dialog" @click="sheetOpen = true"><span class="integrated-calendar__peek-lead">{{ peekText }}</span><span class="integrated-calendar__peek-tail"> · 상세 보기</span></button>
    <SharedSourceOverlay v-if="sharedSource" :source="sharedSource" @close="sharedSource = null" />
    <SharedSourceOverlay v-if="mapSource" :source="mapSource" @close="mapSource = null" />
    <EventDialog v-if="eventDialog" :initial="eventInitial" :busy="eventBusy" :error="eventError" @submit="submitEvent" @delete="confirmDelete = eventDialog.event" @close="eventDialog = null" />
    <ConfirmDialog v-if="confirmDelete" title="일정을 삭제할까요?" :message="`‘${confirmDelete.title}’ 일정을 삭제합니다. 다른 원본 기록은 삭제되지 않습니다.`" :busy="eventBusy" @confirm="removeEvent" @close="confirmDelete = null" />
  </section>
</template>

<style scoped>
/* Calendar design tokens. Light values first, dark (theme "toss") below. The global
   stylesheet forces button colors with !important, so buttons are painted with
   root-prefixed !important rules or with inner elements. */
.integrated-calendar {
  --ic-surface: var(--surface-elevated, #faf8f2);
  --ic-surface-muted: var(--surface-soft, #ebe8dd);
  --ic-panel: var(--panel-bg, #f4f1e8);
  --ic-line: var(--line, #a3b18a);
  --ic-text: var(--text);
  --ic-text-soft: var(--text-soft);
  --ic-accent: var(--brand-strong, #3a5a40);
  --ic-accent-contrast: #ffffff;
  --ic-accent-soft: rgba(88, 129, 87, 0.16);
  --ic-today: var(--brand-strong, #3a5a40);
  --ic-income: #2e6b3f; --ic-income-soft: #dde4d9;
  --ic-expense: #a3402c; --ic-expense-soft: #eeded6;
  --ic-sunday: #b03a3a; --ic-saturday: #2f5d8a;
  --ic-warning: #8a4b0f; --ic-warning-soft: #eae0d2;
  --ic-backdrop: var(--modal-overlay, rgba(52, 78, 65, 0.52));
  --ic-layer-ledger: #3d6b47; --ic-layer-ledger-soft: #e0e4da;
  --ic-layer-places: #9a4a2a; --ic-layer-places-soft: #ede0d6;
  --ic-layer-travel: #2c6275; --ic-layer-travel-soft: #dde3e1;
  --ic-layer-routes: #7a5a10; --ic-layer-routes-soft: #e8e2d2;
  --ic-layer-events: #6b4a8e; --ic-layer-events-soft: #e6e0e4;
}
:root[data-theme='toss'] .integrated-calendar {
  --ic-surface: var(--dark-card, #20292c);
  --ic-surface-muted: var(--dark-surface, #1d2427);
  --ic-panel: var(--dark-surface-raised, #1b2225);
  --ic-line: var(--line, #4a595d);
  --ic-accent: #9bd3a3;
  --ic-accent-contrast: #0f1517;
  --ic-accent-soft: rgba(155, 211, 163, 0.16);
  --ic-today: #9bd3a3;
  --ic-income: var(--dark-income, #86c79b); --ic-income-soft: #364c44;
  --ic-expense: var(--dark-expense, #e88776); --ic-expense-soft: #4c3e3c;
  --ic-sunday: #f09a9a; --ic-saturday: #93bde8;
  --ic-warning: #e0b25a; --ic-warning-soft: #4a4736;
  --ic-backdrop: var(--modal-overlay, rgba(4, 7, 8, 0.78));
  --ic-layer-ledger: #8fcf9f; --ic-layer-ledger-soft: #384e45;
  --ic-layer-places: #e6a383; --ic-layer-places-soft: #4c443f;
  --ic-layer-travel: #86c3d6; --ic-layer-travel-soft: #364b51;
  --ic-layer-routes: #d9bb64; --ic-layer-routes-soft: #494938;
  --ic-layer-events: #c4a8e8; --ic-layer-events-soft: #444555;
}
.integrated-calendar :deep([data-layer='ledger']) { --ic-layer: var(--ic-layer-ledger); --ic-layer-soft: var(--ic-layer-ledger-soft); }
.integrated-calendar :deep([data-layer='places']) { --ic-layer: var(--ic-layer-places); --ic-layer-soft: var(--ic-layer-places-soft); }
.integrated-calendar :deep([data-layer='travel']) { --ic-layer: var(--ic-layer-travel); --ic-layer-soft: var(--ic-layer-travel-soft); }
.integrated-calendar :deep([data-layer='routes']) { --ic-layer: var(--ic-layer-routes); --ic-layer-soft: var(--ic-layer-routes-soft); }
.integrated-calendar :deep([data-layer='events']) { --ic-layer: var(--ic-layer-events); --ic-layer-soft: var(--ic-layer-events-soft); }

.integrated-calendar { word-break: keep-all; display: grid; gap: .625rem; min-width: 0; padding: clamp(.6rem, 1.2vw, 1.5rem); }
.integrated-calendar h1,.integrated-calendar h2,.integrated-calendar h3,.integrated-calendar p { margin: 0; }
.integrated-calendar__layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(20rem, 24rem); align-items: start; gap: 1rem; }
.integrated-calendar__details { position: sticky; top: 1rem; min-width: 0; max-height: calc(100dvh - 2rem); overflow: auto; padding: 1rem; border: 1px solid var(--ic-line); background: var(--ic-panel); }
.integrated-calendar .integrated-calendar__peek { position: sticky; bottom: 0; z-index: 2; box-sizing: border-box; width: 100%; min-height: 2.75rem; padding: .5rem 1rem calc(.5rem + env(safe-area-inset-bottom)); text-align: left; font-weight: 700; background-color: var(--ic-panel) !important; border: 1px solid var(--ic-accent) !important; color: var(--ic-text) !important; -webkit-text-fill-color: var(--ic-text) !important; }
.integrated-calendar .integrated-calendar__peek { display: flex; gap: 0; }
.integrated-calendar__peek-lead { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.integrated-calendar__peek-tail { flex: none; white-space: pre; }
.integrated-calendar__sheet-body { padding: .5rem 1rem 1rem; }
@media (max-width: 60rem) {
  .integrated-calendar__layout { grid-template-columns: minmax(0, 1fr); }
}
</style>
