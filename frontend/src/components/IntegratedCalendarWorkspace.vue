<script setup>
import { computed, defineAsyncComponent, nextTick, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { calendarLedgerDay, calendarMonthGrid, overlapsCalendarDate, validCalendarDate } from '../lib/integratedCalendarCore.js'
import { deleteCalendarEvent, fetchCalendarDay, fetchCalendarEvents, fetchCalendarSummary, fetchCalendarUndated, fetchCalendarSharedOptions, fetchCalendarSharedSummary, fetchCalendarSharedDay, setCalendarSharedSelection, fetchCalendarMapOptions, addCalendarMapLink, setCalendarMapSelection, fetchCalendarMapSummary, fetchCalendarMapDay, fetchTravelSharedExhibit, fetchRecordSharedTravel, fetchCategories, fetchPaymentMethods, fetchTravelPlans, requestRecordMap, saveCalendarEvent } from '../lib/api.js'

const TravelSharedExhibitWorkspace = defineAsyncComponent(() => import('./TravelSharedExhibitWorkspace.vue'))
const RecordMapWorkspace = defineAsyncComponent(() => import('./RecordMapWorkspace.vue'))

const emit = defineEmits(['navigate', 'open-travel-source', 'open-place-source', 'open-ledger-source'])
const now = new Date()
const today = ref(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`)
const year = ref(now.getFullYear()), month = ref(now.getMonth() + 1), selectedDate = ref(today.value)
const yearDraft = ref(now.getFullYear())
const grid = computed(() => calendarMonthGrid(year.value, month.value))
const filter = ref('all'), entryType = ref(''), categoryId = ref(''), paymentMethodId = ref(''), collectionId = ref(''), planId = ref('')
const settingsOpen = ref(!window.matchMedia('(max-width: 60rem)').matches)
const hideAmounts = ref(false), errorMessage = ref(''), eventBusy = ref(false)
const categories = ref([]), paymentMethods = ref([]), collections = ref([]), plans = ref([])
const metadataErrors = ref([]), metadataLoading = ref(false)
const monthSources = reactive({ ledger: null, 'ledger-categories': null, places: null, travel: null, events: null })
const monthErrors = reactive({ ledger: '', 'ledger-categories': '', places: '', travel: '', events: '' })
const daySources = reactive({ ledger: null, places: null, 'travel-records': null, routes: null, events: null })
const dayErrors = reactive({ ledger: '', places: '', 'travel-records': '', routes: '', events: '' })
const loadingMonth = ref(false), loadingDay = ref(false)
const undated = ref(null), undatedError = ref(''), undatedBusy = ref(false), undatedPlacePage = ref(0), undatedTripPage = ref(0)
const sharedOptions = ref([]), sharedSummaries = ref([]), sharedDays = ref([]), includeShared = ref(true), sharedError = ref(''), sharedBusy = ref(false), sharedLoading = ref(false)
const mapOptions = ref([]), mapSummaries = ref([]), mapDays = ref([]), mapError = ref(''), mapLoading = ref(false), mapBusy = ref(false), mapLink = ref('')
const sharedSource = ref(null), sharedSourceRef = ref(null), sharedSourceBusy = ref(false)
const mapSource = ref(null), mapSourceRef = ref(null)
let monthSequence = 0, daySequence = 0, sharedSequence = 0, sharedDaySequence = 0
let mapSequence = 0, mapDaySequence = 0
const draft = reactive({ id: null, version: null, title: '', startDate: today.value, endDate: today.value, allDay: true, startTime: '', endTime: '', memo: '' })
const isEditingEvent = ref(false)

const sourceLabels = { ledger: '가계부', 'ledger-categories': '가계부 분류', places: '장소·기록', travel: '여행', events: '개인 일정', 'travel-records': '여행 기록', routes: '이동 경로' }
const visible = source => filter.value === 'all' || filter.value === source || (filter.value === 'travel' && source === 'travel-records')
const currency = value => hideAmounts.value ? '금액 숨김' : new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(Number(value) || 0)
const compactMoney = value => new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 }).format(Number(value) || 0)
const dailyLedger = computed(() => calendarLedgerDay((daySources.ledger || []).filter(e => (!entryType.value || e.entryType === entryType.value) && (!categoryId.value || String(e.categoryGroupId) === String(categoryId.value)) && (!paymentMethodId.value || String(e.paymentMethodId) === String(paymentMethodId.value))), selectedDate.value))
const dayEvents = computed(() => daySources.events || [])
const dayPeriods = computed(() => (monthSources.travel?.periods || []).filter(period => overlapsCalendarDate(period.startDate, period.endDate, selectedDate.value)))
// An imported map place and a travel record are the same item only when the
// original source ID matches. A separate visit to that place remains separate.
const importedRecordIds = computed(() => new Set((daySources.places || []).filter(item => item.sourceKind === 'TRAVEL_IMPORT').map(item => item.sourceTravelRecordId)))
const travelRecordsById = computed(() => new Map((daySources['travel-records'] || []).map(item => [item.id, item])))
const displayedTravelRecords = computed(() => (daySources['travel-records'] || []).filter(item => !visible('places') || !importedRecordIds.value.has(item.id)))
const linkedImportedRecord = place => place.sourceKind === 'TRAVEL_IMPORT' ? travelRecordsById.value.get(place.sourceTravelRecordId) : null
const visibleSharedMapEntries = share => share.entries.filter(item => item.sourceKind !== 'TRAVEL_IMPORT' || !visible('travel') || !sharedDays.value.some(trip => trip.records.some(record => record.id === item.id)))
function ledgerTime(row) {
  return row.parts.map(item => item.entryTime).filter(Boolean).sort()[0] || ''
}
function ledgerStatus(entry) {
  if (entry.adjustmentVoidedAt) return '처리 되돌림'
  if (entry.adjustmentType === 'PAYMENT_CANCELLATION') return '결제 취소 기록'
  if (entry.adjustmentType === 'REFUND') return '환불 입금'
  if (entry.cancelledAt) return '결제 취소됨'
  if (Number(entry.refundedPrincipal || 0) > 0) return Number(entry.refundedPrincipal) >= Number(entry.amount) ? '환불 완료' : '부분 환불'
  return entry.hasAdjustmentHistory ? '처리 이력 있음' : ''
}
const dayTimeline = computed(() => {
  const rows = []
  if (visible('ledger') && daySources.ledger) for (const row of dailyLedger.value.rows) rows.push({ key: row.key, time: ledgerTime(row), label: `가계부 · ${row.parts[0].title}` })
  if (visible('places')) for (const item of daySources.places || []) rows.push({ key: `place-${item.sourceKind}-${item.id}`, time: item.time, label: `장소 · ${item.placeName} ${item.title}` })
  if (visible('travel')) {
    for (const item of dayPeriods.value) rows.push({ key: `period-${item.id}`, time: '', label: `여행 기간 · ${item.title}` })
    for (const item of displayedTravelRecords.value) rows.push({ key: `travel-${item.id}`, time: item.time, label: `여행 기록 · ${item.title}` })
  }
  if (visible('routes')) for (const item of daySources.routes || []) rows.push({ key: `route-${item.id}`, time: '', label: `경로 · ${item.title}` })
  if (visible('events')) for (const item of dayEvents.value) {
    const ending = item.startDate !== selectedDate.value && item.endDate === selectedDate.value
    rows.push({ key: `event-${item.id}`, time: item.allDay ? '' : ending ? item.endTime : item.startDate === selectedDate.value ? item.startTime : '', label: `개인 일정${ending ? ' 종료' : ''} · ${item.title}` })
  }
  if (includeShared.value && visible('travel')) for (const share of sharedDays.value) for (const item of share.records) rows.push({ key: `shared-${share.type}-${share.shareId}-${item.id}`, time: item.time, label: `공유 여행 기록 · ${item.title}` })
  if (includeShared.value && visible('places')) for (const share of mapDays.value) for (const item of visibleSharedMapEntries(share)) rows.push({ key: `shared-map-${share.id}-${item.sourceKind}-${item.id}`, time: item.time, label: `공유 장소 · ${item.title}` })
  return rows.sort((a, b) => (a.time ? 0 : 1) - (b.time ? 0 : 1) || String(a.time || '').localeCompare(String(b.time || '')) || a.key.localeCompare(b.key))
})
const summary = computed(() => (monthSources.ledger || []).filter(item => item.date >= grid.value.monthStart && item.date <= grid.value.monthEnd).reduce((acc, item) => {
  acc.income += Number(item.income) || 0; acc.expense += Number(item.expense) || 0; return acc
}, { income: 0, expense: 0 }))
const monthVisits = computed(() => (monthSources.places || []).filter(item => item.date >= grid.value.monthStart && item.date <= grid.value.monthEnd).reduce((sum, item) => sum + item.count, 0))
const busiestExpenseDay = computed(() => (monthSources.ledger || []).filter(item => item.date >= grid.value.monthStart && item.date <= grid.value.monthEnd && Number(item.expense) > 0).reduce((best, item) => !best || Number(item.expense) > Number(best.expense) ? item : best, null))
const busiestVisitDay = computed(() => (monthSources.places || []).filter(item => item.date >= grid.value.monthStart && item.date <= grid.value.monthEnd && item.count > 0).reduce((best, item) => !best || item.count > best.count ? item : best, null))
function periodLabel(kind, start, end, date) {
  if (start === end) return kind
  return `${kind} ${date === start ? '시작' : date === end ? '종료' : '기간'}`
}
function compactPeriod(kind, start, end, date) {
  return `${kind}${start !== end && date === start ? '+' : start !== end && date === end ? '−' : ''}`
}

function filteredForDay(date) {
  const items = []
  const money = monthSources.ledger?.find(item => item.date === date)
  if (visible('ledger') && money) items.push({ key: 'ledger', text: `수입 ${currency(money.income)} · 지출 ${currency(money.expense)}`,
    compact: hideAmounts.value ? '숨김' : [Number(money.income) ? `+${compactMoney(money.income)}` : '', Number(money.expense) ? `−${compactMoney(money.expense)}` : ''].filter(Boolean).join('\n') || `거래${money.transactionCount}` })
  const visits = monthSources.places?.find(item => item.date === date)
  if (visible('places') && visits?.count) items.push({ key: 'places', text: `장소 기록 ${visits.count}건`, compact: `장소${visits.count}` })
  if (includeShared.value && visible('places')) for (const share of mapSummaries.value) {
    const count = share.days.find(item => item.date === date)?.count
    if (count) items.push({ key: `shared-map-${share.id}`, text: `공유 장소 ${count}건`, compact: `공유${count}` })
  }
  if (visible('travel')) for (const period of monthSources.travel?.periods || []) if (overlapsCalendarDate(period.startDate, period.endDate, date)) {
    const label = periodLabel('여행', period.startDate, period.endDate, date)
    items.push({ key: `travel-${period.id}`, text: `${label} · ${period.title}`, compact: compactPeriod('여행', period.startDate, period.endDate, date) })
  }
  if (visible('travel')) for (const item of monthSources.travel?.records || []) if (item.date === date) {
    const count = item.count - (visible('places') && monthSources.places ? (item.importedCount || 0) : 0)
    if (count > 0) items.push({ key: `record-${item.type}`, text: `여행 기록 ${count}건`, compact: `기록${count}` })
  }
  const route = monthSources.travel?.routes?.find(item => item.date === date)
  if (visible('routes') && route?.count) items.push({ key: 'routes', text: `경로 ${route.count}건`, compact: `경로${route.count}` })
  if (visible('events')) for (const event of monthSources.events || []) if (overlapsCalendarDate(event.startDate, event.endDate, date)) {
    const label = periodLabel('일정', event.startDate, event.endDate, date)
    items.push({ key: `event-${event.id}`, text: `${label} · ${event.title}`, compact: compactPeriod('일정', event.startDate, event.endDate, date) })
  }
  if (includeShared.value && visible('travel')) for (const share of sharedSummaries.value) {
    for (const period of share.data.periods) if (overlapsCalendarDate(period.startDate, period.endDate, date)) {
      const label = periodLabel('공유 여행', period.startDate, period.endDate, date)
      items.push({ key: `shared-${share.type}-${share.shareId}`, text: `${label} · ${period.title}`, compact: compactPeriod('공유', period.startDate, period.endDate, date) })
    }
    for (const record of share.data.records) if (record.date === date) items.push({ key: `shared-record-${share.type}-${share.shareId}-${record.type}`, text: `공유 기록 ${record.count}건`, compact: `공유${record.count}` })
  }
  if (includeShared.value && visible('routes')) for (const share of sharedSummaries.value) {
    const route = share.data.routes.find(item => item.date === date)
    if (route?.count) items.push({ key: `shared-route-${share.type}-${share.shareId}`, text: `공유 경로 ${route.count}건`, compact: `공유${route.count}` })
  }
  return items
}

async function loadMonth() {
  const sequence = ++monthSequence
  loadingMonth.value = true
  const params = { from: grid.value.visibleStart, to: grid.value.visibleEnd }
  const tasks = [
    ['ledger', { ...params, type: entryType.value, categoryId: categoryId.value, paymentMethodId: paymentMethodId.value }],
    ['ledger-categories', { from: grid.value.monthStart, to: grid.value.monthEnd, type: entryType.value, categoryId: categoryId.value, paymentMethodId: paymentMethodId.value }],
    ['places', { ...params, collectionId: collectionId.value, planId: planId.value }], ['travel', { ...params, planId: planId.value, collectionId: collectionId.value }], ['events', params],
  ]
  const sharedTask = loadShared()
  const mapTask = loadSharedMaps()
  await Promise.all([...tasks.map(async ([source, query]) => {
    monthSources[source] = null; monthErrors[source] = ''
    try { const data = await fetchCalendarSummary(source, query); if (sequence === monthSequence) monthSources[source] = data }
    catch { if (sequence === monthSequence) monthErrors[source] = `${sourceLabels[source]} 월 정보를 불러오지 못했습니다.` }
  }), sharedTask, mapTask])
  if (sequence === monthSequence) loadingMonth.value = false
}

async function loadDay() {
  const sequence = ++daySequence
  loadingDay.value = true
  const date = selectedDate.value
  await Promise.all(Object.keys(daySources).map(async source => {
    daySources[source] = null; dayErrors[source] = ''
    try { const data = source === 'events' ? await fetchCalendarEvents(date) : await fetchCalendarDay(source, date, { collectionId: source === 'places' ? collectionId.value : '', planId: ['places', 'travel-records', 'routes'].includes(source) ? planId.value : '' }); if (sequence === daySequence) daySources[source] = data }
    catch { if (sequence === daySequence) dayErrors[source] = `${sourceLabels[source]} 상세 정보를 불러오지 못했습니다.` }
  }))
  if (sequence === daySequence) loadingDay.value = false
  loadSharedDay()
  loadSharedMapDay()
}
async function loadSharedMaps() {
  const sequence = ++mapSequence
  mapSource.value = null
  mapDaySequence += 1; mapSummaries.value = []; mapDays.value = []; mapError.value = ''; mapLoading.value = true
  try {
    const options = []; let page = 0, totalPages = 1
    while (page < totalPages) { const result = await fetchCalendarMapOptions(page++); options.push(...result.items); totalPages = result.totalPages }
    if (sequence !== mapSequence) return
    mapOptions.value = options
    if (!includeShared.value) return
    const results = await Promise.allSettled(options.filter(item => item.selected).map(item => fetchCalendarMapSummary(item.id, grid.value.visibleStart, grid.value.visibleEnd)))
    if (sequence !== mapSequence) return
    mapSummaries.value = results.filter(item => item.status === 'fulfilled').map(item => item.value)
    if (results.some(item => item.status === 'rejected')) mapError.value = '일부 공유 지도 권한이 변경되었거나 조회에 실패했습니다.'
    loadSharedMapDay()
  } catch { if (sequence === mapSequence) { mapOptions.value = []; mapError.value = '공유 지도를 불러오지 못했습니다.' } }
  finally { if (sequence === mapSequence) mapLoading.value = false }
}
async function openSharedMapSource(share, item) {
  mapSource.value = { grantId: share.id, focus: { token: Date.now(), placeId: item.placeId, recordId: item.id, sourceKind: item.sourceKind, date: selectedDate.value } }
  await nextTick()
  mapSourceRef.value?.scrollIntoView({ block: 'start', behavior: 'smooth' })
}
async function loadSharedMapDay() {
  const sequence = ++mapDaySequence
  if (!includeShared.value) { mapDays.value = []; return }
  const current = [...mapSummaries.value]
  const results = await Promise.allSettled(current.map(item => fetchCalendarMapDay(item.id, selectedDate.value)))
  if (sequence !== mapDaySequence) return
  mapDays.value = results.filter(item => item.status === 'fulfilled').map(item => item.value)
  if (results.some(item => item.status === 'rejected')) {
    const failed = new Set(results.flatMap((item, index) => item.status === 'rejected' ? [current[index].id] : []))
    mapSummaries.value = mapSummaries.value.filter(item => !failed.has(item.id))
    mapError.value = '일부 공유 지도 권한이 변경되었거나 상세 조회에 실패했습니다.'
  }
}
async function addSharedMap() {
  const value = mapLink.value.trim()
  const token = /^[A-Za-z0-9_-]{43}$/.test(value) ? value : value.match(/#record-map-share\/([A-Za-z0-9_-]{43})$/)?.[1]
  if (!token) { mapError.value = '유효한 기록 지도 공유 주소를 입력해 주세요.'; return }
  mapBusy.value = true; mapError.value = ''
  try { await addCalendarMapLink(token); mapLink.value = ''; await loadSharedMaps() }
  catch { mapError.value = '공유 지도를 추가하지 못했습니다. 링크가 만료되었거나 취소되었는지 확인해 주세요.' }
  finally { mapBusy.value = false }
}
async function toggleSharedMap(item, event) {
  mapBusy.value = true; mapError.value = ''
  try { await setCalendarMapSelection(item.id, event.target.checked); await loadSharedMaps() }
  catch { event.target.checked = item.selected; mapError.value = '공유 지도 선택을 저장하지 못했습니다.' }
  finally { mapBusy.value = false }
}
async function loadShared() {
  const sequence = ++sharedSequence
  sharedSource.value = null
  sharedDaySequence += 1
  sharedSummaries.value = []; sharedDays.value = []; sharedError.value = ''; sharedLoading.value = true
  try {
    const types = ['exhibit', 'record']
    const all = await Promise.allSettled(types.map(async type => {
      const items = []; let page = 0, totalPages = 1
      while (page < totalPages) {
        const result = await fetchCalendarSharedOptions(type, page)
        items.push(...(result.items || []).map(item => ({ ...item, type })))
        totalPages = Number(result.totalPages) || 0; page += 1
      }
      return items
    }))
    if (sequence !== sharedSequence) return
    sharedOptions.value = all.filter(item => item.status === 'fulfilled').flatMap(item => item.value)
    const failedTypes = all.flatMap((item, index) => item.status === 'rejected' ? [types[index] === 'exhibit' ? '공유 전시' : '공유 여행 기록'] : [])
    if (failedTypes.length) sharedError.value = `${failedTypes.join('·')} 목록을 불러오지 못했습니다. 다른 공유 자료는 계속 표시됩니다.`
    if (!includeShared.value) { sharedSummaries.value = []; sharedDays.value = []; return }
    const selected = sharedOptions.value.filter(item => item.selected)
    const results = await Promise.allSettled(selected.map(item => fetchCalendarSharedSummary(item.type, item.id, { from: grid.value.visibleStart, to: grid.value.visibleEnd })))
    if (sequence !== sharedSequence) return
    // Two received shares can point at the same actual plan. Show it once,
    // retaining one currently authorized share for subsequent detail reads.
    sharedSummaries.value = [...new Map(results.filter(item => item.status === 'fulfilled').map(item => [item.value.planId, item.value])).values()]
    if (results.some(item => item.status === 'rejected')) sharedError.value = [sharedError.value, '일부 공유 자료의 권한이 변경되었거나 조회에 실패했습니다. 다시 불러와 주세요.'].filter(Boolean).join(' ')
    loadSharedDay()
  } catch { if (sequence === sharedSequence) { sharedOptions.value = []; sharedSummaries.value = []; sharedDays.value = []; sharedError.value = '공유 자료를 불러오지 못했습니다.' } }
  finally { if (sequence === sharedSequence) sharedLoading.value = false }
}
async function openSharedTravelSource(share) {
  sharedSource.value = null; sharedSourceBusy.value = true; sharedError.value = ''
  try {
    const detail = share.type === 'exhibit' ? await fetchTravelSharedExhibit(share.shareId) : await fetchRecordSharedTravel(share.shareId)
    // Permission and current selection may have changed while fetching.
    if (!includeShared.value || !sharedSummaries.value.some(item => item.type === share.type && item.shareId === share.shareId)) return
    sharedSource.value = detail
    await nextTick()
    sharedSourceRef.value?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  } catch { sharedError.value = '공유 여행 원본을 열 수 없습니다. 공유 권한을 확인해 주세요.' }
  finally { sharedSourceBusy.value = false }
}
async function loadSharedDay() {
  const sequence = ++sharedDaySequence
  if (!includeShared.value) { sharedDays.value = []; return }
  const current = [...sharedSummaries.value]
  const results = await Promise.allSettled(current.map(item => fetchCalendarSharedDay(item.type, item.shareId, selectedDate.value)))
  if (sequence !== sharedDaySequence) return
  sharedDays.value = results.filter(item => item.status === 'fulfilled').map(item => item.value)
  if (results.some(item => item.status === 'rejected')) {
    const failed = new Set(results.flatMap((item, index) => item.status === 'rejected' ? [`${current[index].type}:${current[index].shareId}`] : []))
    sharedSummaries.value = sharedSummaries.value.filter(item => !failed.has(`${item.type}:${item.shareId}`))
    sharedError.value = '일부 공유 상세 정보의 권한이 변경되었거나 조회에 실패했습니다.'
  }
}
async function toggleShared(item, event) {
  const checked = event.target.checked
  sharedBusy.value = true; sharedError.value = ''
  try { await setCalendarSharedSelection(item.type, item.id, checked); await loadShared() }
  catch { event.target.checked = item.selected; sharedError.value = '공유 자료 선택을 저장하지 못했습니다. 권한을 확인하고 다시 시도해 주세요.' }
  finally { sharedBusy.value = false }
}
async function loadUndated() {
  undatedBusy.value = true; undatedError.value = ''
  try { undated.value = await fetchCalendarUndated({ placePage: undatedPlacePage.value, tripPage: undatedTripPage.value }) }
  catch { undatedError.value = '날짜 미지정 자료를 불러오지 못했습니다.' }
  finally { undatedBusy.value = false }
}
function changeUndatedPage(type, step) {
  if (type === 'places') undatedPlacePage.value = Math.max(0, undatedPlacePage.value + step)
  else undatedTripPage.value = Math.max(0, undatedTripPage.value + step)
  loadUndated()
}
function openTravelRecord(record) {
  emit('open-travel-source', { sourceType: record.type === 'MEMORY' ? 'memory' : 'ledger', planId: record.planId, recordId: record.id })
}

function moveMonth(offset) {
  const date = new Date(year.value, month.value - 1 + offset, 1)
  if (date.getFullYear() < 1900 || date.getFullYear() > 9998) return
  year.value = date.getFullYear(); yearDraft.value = year.value; month.value = date.getMonth() + 1
  selectedDate.value = `${year.value}-${String(month.value).padStart(2, '0')}-01`
}
function chooseDate(date) {
  selectedDate.value = date
  if (!date.startsWith(`${year.value}-${String(month.value).padStart(2, '0')}`)) { year.value = Number(date.slice(0, 4)); yearDraft.value = year.value; month.value = Number(date.slice(5, 7)) }
}
function goToday() { const current = new Date(); year.value = current.getFullYear(); yearDraft.value = year.value; month.value = current.getMonth() + 1; today.value = `${year.value}-${String(month.value).padStart(2, '0')}-${String(current.getDate()).padStart(2, '0')}`; selectedDate.value = today.value }
function selectMonthStart() { selectedDate.value = `${year.value}-${String(month.value).padStart(2, '0')}-01` }
function changeYear() { const value = Number(yearDraft.value); if (Number.isInteger(value) && value >= 1900 && value <= 9998 && year.value !== value) { year.value = value; selectMonthStart() } yearDraft.value = year.value }
function beginEvent(event = null) {
  Object.assign(draft, event ? { ...event, startTime: event.startTime?.slice(0, 5) || '', endTime: event.endTime?.slice(0, 5) || '', memo: event.memo || '' }
    : { id: null, version: null, title: '', startDate: selectedDate.value, endDate: selectedDate.value, allDay: true, startTime: '', endTime: '', memo: '' })
  errorMessage.value = ''; isEditingEvent.value = true
}
async function submitEvent() {
  errorMessage.value = ''
  const endDate = draft.endDate || draft.startDate
  if (!draft.title.trim() || !validCalendarDate(draft.startDate) || !validCalendarDate(endDate) || endDate < draft.startDate || (!draft.allDay && draft.startDate === endDate && draft.startTime && draft.endTime && draft.endTime < draft.startTime)) {
    errorMessage.value = '제목과 시작·종료 날짜 및 시간을 확인해 주세요.'; return
  }
  eventBusy.value = true
  try {
    await saveCalendarEvent(draft.id, { title: draft.title.trim(), startDate: draft.startDate, endDate, allDay: draft.allDay,
      startTime: draft.allDay ? null : draft.startTime || null, endTime: draft.allDay ? null : draft.endTime || null, memo: draft.memo, version: draft.version })
    isEditingEvent.value = false; await Promise.all([loadMonth(), loadDay()])
  } catch (error) { errorMessage.value = error.status === 409 ? '다른 화면에서 일정이 변경되었습니다. 다시 불러온 뒤 수정해 주세요.' : error.message }
  finally { eventBusy.value = false }
}
async function removeEvent(event) {
  if (!window.confirm(`‘${event.title}’ 일정을 삭제할까요? 다른 원본 기록은 삭제되지 않습니다.`)) return
  eventBusy.value = true; errorMessage.value = ''
  try { await deleteCalendarEvent(event.id, event.version); await Promise.all([loadMonth(), loadDay()]) }
  catch (error) { errorMessage.value = error.status === 409 ? '다른 화면에서 일정이 변경되었습니다. 다시 불러와 주세요.' : error.message }
  finally { eventBusy.value = false }
}
watch([year, month, entryType, categoryId, paymentMethodId, collectionId, planId], loadMonth)
watch([selectedDate, collectionId, planId], loadDay)
watch(includeShared, () => { loadShared(); loadSharedMaps() })
let lastVisibilityRefresh = 0
function refreshWhenVisible() {
  if (document.visibilityState !== 'visible' || Date.now() - lastVisibilityRefresh < 1000) return
  lastVisibilityRefresh = Date.now()
  const current = new Date()
  today.value = `${current.getFullYear()}-${String(current.getMonth() + 1).padStart(2, '0')}-${String(current.getDate()).padStart(2, '0')}`
  loadMonth(); loadDay()
}
async function loadFilterOptions() {
  metadataLoading.value = true; metadataErrors.value = []
  const results = await Promise.allSettled([fetchCategories(), fetchPaymentMethods(), requestRecordMap('/overview?page=0&size=1'), fetchTravelPlans()])
  const setters = [value => { categories.value = value || [] }, value => { paymentMethods.value = value || [] }, value => { collections.value = value?.collections || [] }, value => { plans.value = value || [] }]
  const labels = ['가계부 분류', '결제수단', '장소 분류', '여행']
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') setters[index](result.value)
    else metadataErrors.value.push(labels[index])
  })
  metadataLoading.value = false
}
onMounted(() => {
  window.addEventListener('focus', refreshWhenVisible)
  document.addEventListener('visibilitychange', refreshWhenVisible)
  loadMonth(); loadDay(); loadFilterOptions()
})
onBeforeUnmount(() => {
  monthSequence++; daySequence++; sharedSequence++; sharedDaySequence++; mapSequence++; mapDaySequence++
  window.removeEventListener('focus', refreshWhenVisible)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>

<template>
  <section class="integrated-calendar" aria-label="통합 달력">
    <header class="integrated-calendar__header">
      <div><h1>통합 달력</h1><p>내 가계부와 기록, 여행, 개인 일정을 날짜별로 확인합니다.</p></div>
      <div class="integrated-calendar__month-nav">
        <button type="button" aria-label="이전 달" @click="moveMonth(-1)">‹</button>
        <label>연도 <input v-model="yearDraft" type="number" min="1900" max="9998" aria-label="연도" @change="changeYear"></label>
        <label>월 <select v-model.number="month" aria-label="월" @change="selectMonthStart"><option v-for="m in 12" :key="m" :value="m">{{ m }}월</option></select></label>
        <button type="button" aria-label="다음 달" @click="moveMonth(1)">›</button><button type="button" @click="goToday">오늘</button><button type="button" @click="loadMonth(); loadDay()">다시 불러오기</button>
      </div>
    </header>
    <details class="integrated-calendar__settings" :open="settingsOpen" @toggle="settingsOpen = $event.target.open">
    <summary>필터와 공유 설정</summary>
    <div class="integrated-calendar__filters" aria-label="달력 필터">
      <label>종류 <select v-model="filter"><option value="all">전체</option><option value="ledger">가계부</option><option value="places">장소·기록</option><option value="travel">여행</option><option value="routes">경로</option><option value="events">개인 일정</option></select></label>
      <label>거래 구분 <select v-model="entryType"><option value="">전체</option><option value="INCOME">수입</option><option value="EXPENSE">지출</option></select></label>
      <label>분류 <select v-model="categoryId"><option value="">전체</option><option v-for="item in categories" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
      <label>결제수단 <select v-model="paymentMethodId"><option value="">전체</option><option v-for="item in paymentMethods" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
      <label>장소 분류 <select v-model="collectionId"><option value="">전체</option><option v-for="item in collections" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
      <label>여행 <select v-model="planId"><option value="">전체</option><option v-for="item in plans" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
      <label><input v-model="hideAmounts" type="checkbox"> 금액 숨기기</label>
      <label><input v-model="includeShared" type="checkbox"> 선택한 공유 자료 표시</label>
    </div>
    <p v-if="planId"><small>여행 필터에서는 실제로 해당 여행과 연결된 장소만 표시합니다. 별도 방문은 여행과의 관계를 추측하지 않습니다.</small></p>
    <details class="integrated-calendar__undated"><summary>공유받은 여행 선택</summary><p>선택한 여행만 달력에 표시합니다. 선택 해제해도 원본 공유는 삭제되지 않으며, 본인 가계부 합계에 더하지 않습니다.</p>
      <p v-if="sharedError" role="alert" class="integrated-calendar__error">{{ sharedError }} <button type="button" @click="loadShared">재시도</button></p>
      <p v-if="sharedLoading">공유 자료를 확인하는 중입니다.</p><p v-else-if="!sharedOptions.length && !sharedError">선택할 수 있는 공유 여행이 없습니다.</p>
      <div v-for="item in sharedOptions" :key="`${item.type}-${item.id}`"><label><input type="checkbox" :checked="item.selected" :disabled="sharedBusy || sharedLoading" @change="toggleShared(item, $event)"> {{ item.title }} · {{ item.owner }} ({{ item.type === 'exhibit' ? '공유 전시' : '공유 기록' }})</label></div>
    </details>
    <details class="integrated-calendar__undated"><summary>공유받은 기록 지도 선택</summary><p>받은 기록 지도 공유 주소를 추가하면 이 계정에서만 선택할 수 있습니다. 주소 자체는 저장하지 않습니다. 방문 기록이 포함된 공유만 날짜별로 표시됩니다.</p>
      <form @submit.prevent="addSharedMap"><label>기록 지도 공유 주소 <input v-model="mapLink" type="text" autocomplete="off" spellcheck="false"></label> <button type="submit" :disabled="mapBusy">공유 지도 추가</button></form>
      <p v-if="mapLoading">공유 지도를 확인하는 중입니다.</p><p v-else-if="!mapOptions.length && !mapError">추가된 공유 지도가 없습니다.</p>
      <div v-for="item in mapOptions" :key="item.id"><label><input type="checkbox" :checked="item.selected" :disabled="mapBusy || mapLoading" @change="toggleSharedMap(item, $event)"> {{ item.title }} · {{ item.includesVisits ? '방문 기록 포함' : '장소만 공유' }}</label></div>
    </details>
    </details>
    <div v-if="Object.values(monthErrors).some(Boolean)" class="integrated-calendar__error" role="alert">일부 자료를 불러오지 못했습니다. 다시 불러오기를 눌러 주세요. <span v-for="message in Object.values(monthErrors).filter(Boolean)" :key="message">{{ message }} </span></div>
    <p v-if="sharedError" role="alert" class="integrated-calendar__error">{{ sharedError }} <button type="button" @click="loadShared">재시도</button></p>
    <p v-if="mapError" role="alert" class="integrated-calendar__error">{{ mapError }} <button type="button" @click="loadSharedMaps">재시도</button></p>
    <p v-if="metadataErrors.length" role="alert" class="integrated-calendar__error">{{ metadataErrors.join('·') }} 필터 목록을 불러오지 못했습니다. 다른 자료는 계속 표시됩니다. <button type="button" :disabled="metadataLoading" @click="loadFilterOptions">재시도</button></p>
    <div class="integrated-calendar__summary" aria-label="이번 달 요약">
      <span>{{ year }}년 {{ month }}월 · 현재 필터 기준</span>
      <template v-if="visible('ledger')"><span v-if="monthSources.ledger">수입 {{ currency(summary.income) }} · 지출 {{ currency(summary.expense) }} · 수지 {{ currency(summary.income - summary.expense) }}</span><span v-else>가계부 합계 {{ monthErrors.ledger ? '조회 실패' : '불러오는 중' }}</span></template>
      <template v-if="visible('places')"><span v-if="monthSources.places">장소 기록 {{ monthVisits }}건</span><span v-else>장소 기록 {{ monthErrors.places ? '조회 실패' : '불러오는 중' }}</span></template>
      <span v-if="visible('ledger') && busiestExpenseDay">지출이 가장 많았던 날 {{ busiestExpenseDay.date }} · {{ currency(busiestExpenseDay.expense) }}</span>
      <span v-if="visible('ledger') && monthSources['ledger-categories']?.length">지출이 가장 많았던 분류 {{ monthSources['ledger-categories'][0].name }} · {{ currency(monthSources['ledger-categories'][0].amount) }}</span>
      <span v-if="visible('places') && busiestVisitDay">장소 기록이 가장 많았던 날 {{ busiestVisitDay.date }} · {{ busiestVisitDay.count }}건</span>
      <span v-if="visible('travel') && monthSources.travel">기간이 겹치는 여행 {{ monthSources.travel.periods.filter(period => period.startDate <= grid.monthEnd && period.endDate >= grid.monthStart).length }}개</span>
      <span v-if="includeShared && visible('travel')">공유 여행 {{ sharedLoading ? '조회 중' : sharedError ? '일부 조회 실패' : `${sharedSummaries.length}개` }} · 가계부 합계 제외</span>
    </div>
    <details class="integrated-calendar__undated" @toggle="$event.target.open && loadUndated()"><summary>날짜 미지정 자료</summary>
      <p>방문 날짜가 없는 장소와 기간이 없는 여행입니다. 기록일을 임의로 지정하지 않습니다.</p>
      <p v-if="undatedError" class="integrated-calendar__error" role="alert">{{ undatedError }} <button type="button" @click="loadUndated">재시도</button></p>
      <p v-else-if="undatedBusy">불러오는 중</p>
      <template v-if="undated && !undatedBusy"><section v-for="[type, label] in [['places', '날짜 없는 장소'], ['trips', '기간 없는 여행']]" :key="type"><strong>{{ label }} · {{ undated[type].total }}개</strong>
        <ul><li v-for="item in undated[type].items" :key="item.id">{{ item.title }} <button type="button" @click="type === 'places' ? emit('open-place-source', { placeId: item.id }) : emit('open-travel-source', { sourceType: 'plan', planId: item.id })">원본 보기</button></li></ul>
        <div><button type="button" :disabled="undated[type].page === 0" @click="changeUndatedPage(type, -1)">이전</button> <span>{{ undated[type].page + 1 }}쪽</span> <button type="button" :disabled="(undated[type].page + 1) * undated[type].size >= undated[type].total" @click="changeUndatedPage(type, 1)">다음</button></div>
      </section></template>
    </details>
    <div class="integrated-calendar__layout">
      <div class="integrated-calendar__month" :aria-busy="loadingMonth">
        <p class="integrated-calendar__mobile-legend">금액: + 수입, − 지출 · 여행·일정: + 시작, − 종료</p>
        <div class="integrated-calendar__weekdays" aria-hidden="true"><span v-for="day in ['일','월','화','수','목','금','토']" :key="day">{{ day }}</span></div>
        <div class="integrated-calendar__grid">
          <button v-for="day in grid.days" :key="day.date" type="button" class="integrated-calendar__cell" :class="{ 'is-other': !day.inMonth, 'is-selected': day.date === selectedDate, 'is-today': day.date === today }" :aria-pressed="day.date === selectedDate" :aria-label="`${day.date} ${day.date === today ? '오늘 · ' : ''}${filteredForDay(day.date).length}개 항목${filteredForDay(day.date).length ? ' · ' + filteredForDay(day.date).map(item => item.text).join(', ') : ''}`" @click="chooseDate(day.date)">
            <strong>{{ Number(day.date.slice(-2)) }}<small v-if="day.date === today"> 오늘</small></strong>
            <span v-for="item in filteredForDay(day.date).slice(0, 2)" :key="item.key" class="integrated-calendar__cell-item"><span class="integrated-calendar__wide-text">{{ item.text }}</span><span class="integrated-calendar__compact-text">{{ item.compact || item.text }}</span></span>
            <span v-if="filteredForDay(day.date).length > 2" class="integrated-calendar__more">+{{ filteredForDay(day.date).length - 2 }}<span class="integrated-calendar__wide-text"> 더보기</span></span>
          </button>
        </div>
      </div>
      <aside class="integrated-calendar__details" :aria-busy="loadingDay">
        <div class="integrated-calendar__details-head"><h2>{{ selectedDate }} 상세</h2><button type="button" @click="beginEvent()">일정 추가</button></div>
        <section v-if="dayTimeline.length" aria-label="시간순 요약"><h3>시간순 요약</h3><ol class="integrated-calendar__timeline"><li v-for="item in dayTimeline" :key="item.key"><strong>{{ item.time ? String(item.time).slice(0, 5) : '시간 미지정·종일' }}</strong> {{ item.label }}</li></ol><small>가계부의 00:00은 저장된 값이며 미입력 여부를 구분할 수 없습니다.</small></section>
        <p v-if="errorMessage" role="alert" class="integrated-calendar__error">{{ errorMessage }}</p>
        <form v-if="isEditingEvent" class="integrated-calendar__form" @submit.prevent="submitEvent">
          <h3>{{ draft.id ? '일정 수정' : '개인 일정 추가' }}</h3>
          <label>제목 <input v-model="draft.title" maxlength="160" required></label>
          <label>시작 날짜 <input v-model="draft.startDate" type="date" required></label><label>종료 날짜 (선택) <input v-model="draft.endDate" type="date"></label>
          <label><input v-model="draft.allDay" type="checkbox"> 종일</label>
          <template v-if="!draft.allDay"><label>시작 시간 <input v-model="draft.startTime" type="time"></label><label>종료 시간 <input v-model="draft.endTime" type="time"></label></template>
          <label>메모 <textarea v-model="draft.memo" maxlength="4000"></textarea></label>
          <div><button type="submit" :disabled="eventBusy">저장</button> <button type="button" :disabled="eventBusy" @click="isEditingEvent = false">취소</button></div>
        </form>
        <section v-if="visible('ledger')"><h3>가계부</h3><p v-if="dayErrors.ledger" class="integrated-calendar__error">{{ dayErrors.ledger }} <button @click="loadDay">재시도</button></p><p v-else-if="!daySources.ledger">불러오는 중</p><p v-else-if="!dailyLedger.rows.length">거래가 없습니다.</p>
          <article v-for="row in dailyLedger.rows" :key="row.key" class="integrated-calendar__entry">
            <strong>{{ row.parts[0].title }}</strong><span>{{ ledgerTime(row) ? `${String(ledgerTime(row)).slice(0,5)} (저장된 시간${row.isSplit ? ' 중 가장 빠름' : ''})` : '시간 미지정' }} · {{ row.parts[0].entryType === 'INCOME' ? '수입' : '지출' }} · {{ currency(row.displayedAmount) }}</span>
            <small v-if="row.parts.some(part => part.financiallyExcluded)">{{ row.isSplit ? `일부 수단 합계 제외 · 집계 금액 ${currency(row.parts.filter(part => !part.financiallyExcluded).reduce((sum, part) => sum + Number(part.amount || 0), 0))}` : '합계 제외' }}</small><small v-if="ledgerStatus(row.parts[0])">{{ ledgerStatus(row.parts[0]) }}<template v-if="row.parts[0].adjustmentOriginId"> · 원거래 {{ row.parts[0].adjustmentOriginId }}</template></small><small v-if="row.parts[0].travelRecordId">여행 지출과 연결된 가계부 거래</small>
            <details><summary>{{ row.isSplit ? `거래 1건 · 결제수단 ${row.paymentMethodCount}개 상세` : '거래 상세' }}</summary><div v-for="part in row.parts" :key="part.id">{{ part.entryTime ? `${String(part.entryTime).slice(0, 5)} · ` : '' }}{{ part.paymentMethodName }} · {{ currency(part.amount) }} <span v-if="ledgerStatus(part)">· {{ ledgerStatus(part) }} </span><span v-if="part.installmentMonths">· {{ part.installmentMonths }}개월 {{ part.installmentInterestType === 'FREE' ? '무이자' : part.installmentInterestType === 'INTEREST' ? '유이자' : '' }} 할부</span><span v-if="part.adjustmentType === 'REFUND'"> · 기준 {{ currency(part.adjustmentPrincipal) }} · 차감 {{ currency(part.adjustmentDeduction) }} · 추가 {{ currency(part.adjustmentExtra) }}</span><span v-if="part.foreignCurrencyCode && part.foreignAmount != null && !hideAmounts"> · {{ part.foreignCurrencyCode }} {{ part.foreignAmount }}</span><span v-if="part.financiallyExcluded"> · 합계 제외</span></div></details><button type="button" @click="emit('open-ledger-source', { date: selectedDate, entryId: row.parts[0].id })">가계부 원본 보기</button><button v-if="row.parts[0].travelPlanId && row.parts[0].travelRecordId" type="button" @click="emit('open-travel-source', { sourceType: 'ledger', planId: row.parts[0].travelPlanId, recordId: row.parts[0].travelRecordId })">연결된 여행 지출 보기</button>
          </article>
        </section>
        <section v-if="visible('places')"><h3>장소·기록</h3><p v-if="dayErrors.places" class="integrated-calendar__error">{{ dayErrors.places }} <button @click="loadDay">재시도</button></p><p v-else-if="!daySources.places">불러오는 중</p><p v-else-if="!daySources.places.length">방문 기록이 없습니다.</p>
          <article v-for="place in daySources.places || []" :key="`${place.sourceKind}-${place.id}`" class="integrated-calendar__entry"><strong>{{ place.placeName }} · {{ place.title }}</strong><span>{{ place.time ? place.time.slice(0,5) : '시간 미지정' }} · {{ place.categories.join(', ') || '분류 없음' }}</span><small v-if="place.sourceKind === 'TRAVEL_IMPORT'">여행에서 가져온 기록<span v-if="linkedImportedRecord(place)"> · {{ linkedImportedRecord(place).planName }}</span></small><small v-if="linkedImportedRecord(place)?.type === 'LEDGER'">{{ linkedImportedRecord(place).linkedToLedger ? '가계부 연결' : '여행 지출 · 가계부 미반영' }} · {{ currency(linkedImportedRecord(place).amountKrw) }}</small><small v-if="place.memo">{{ place.memo }}</small><small>사진 {{ place.photoCount }}장</small><img v-if="place.thumbnailPhotoId" :src="`/api/record-map/photos/${place.thumbnailPhotoId}/content?thumbnail=true`" alt="장소 사진 미리보기" loading="lazy"><img v-else-if="place.thumbnailMediaId" :src="`/api/travel/media/${place.thumbnailMediaId}/content?thumbnail=true`" alt="여행 사진 미리보기" loading="lazy"><button type="button" @click="emit('open-place-source', { placeId: place.placeId, visitId: place.sourceKind === 'VISIT' ? place.id : null })">장소 원본 보기</button><button v-if="linkedImportedRecord(place)" type="button" @click="openTravelRecord(linkedImportedRecord(place))">여행 기록 원본 보기</button></article>
        </section>
        <section v-if="includeShared && visible('places') && mapDays.length"><h3>공유받은 장소·기록 · 내 자료와 별도</h3><article v-for="share in mapDays" :key="share.id" class="integrated-calendar__entry"><strong>공유 · {{ share.title }}</strong><div v-for="item in visibleSharedMapEntries(share)" :key="`${item.sourceKind}-${item.id}`"><strong>{{ item.placeName }} · {{ item.title }}</strong> · {{ item.time ? item.time.slice(0, 5) : '시간 미지정' }}<p v-if="item.memo">{{ item.memo }}</p><small>사진 {{ item.photoCount }}장</small><a v-if="item.thumbnailId" :href="`/api/integrated-calendar/shared-map/${share.id}/photos/${item.sourceKind === 'VISIT' ? 'visit' : 'travel'}/${item.thumbnailId}`" target="_blank" rel="noopener noreferrer"><img :src="`/api/integrated-calendar/shared-map/${share.id}/photos/${item.sourceKind === 'VISIT' ? 'visit' : 'travel'}/${item.thumbnailId}?thumbnail=true`" alt="공유 장소 사진 원본 보기" loading="lazy"></a><button type="button" @click="openSharedMapSource(share, item)">공유 장소 원본 보기</button></div><small v-if="!share.entries.length">이 날짜의 공유 기록이 없습니다.</small><small v-else-if="!visibleSharedMapEntries(share).length">같은 원본은 공유 여행 기록에 표시됩니다.</small></article></section>
        <section v-if="visible('travel')"><h3>여행</h3><p v-if="monthErrors.travel || dayErrors['travel-records']" class="integrated-calendar__error">여행 정보를 불러오지 못했습니다. <button @click="loadMonth(); loadDay()">재시도</button></p>
          <article v-for="period in dayPeriods" :key="period.id" class="integrated-calendar__entry"><strong>{{ period.title }}</strong><span>{{ period.status === 'COMPLETED' ? '완료' : period.status === 'PLANNED' ? '계획' : period.status }} · {{ period.startDate }} ~ {{ period.endDate }}</span><button type="button" @click="emit('open-travel-source', { sourceType: 'plan', planId: period.id })">여행 원본 보기</button></article>
          <article v-for="record in displayedTravelRecords" :key="record.id" class="integrated-calendar__entry"><strong>{{ record.title }}</strong><span>{{ record.time ? record.time.slice(0,5) : '시간 미지정' }} · {{ record.planName }} · {{ record.type === 'LEDGER' ? (record.linkedToLedger ? '가계부 연결' : '여행 지출 · 가계부 미반영') : '여행 기록' }}</span><small v-if="record.placeName">{{ record.placeName }}</small><small v-if="record.type === 'LEDGER'">{{ currency(record.amountKrw) }}<template v-if="record.currencyCode && record.currencyCode !== 'KRW' && record.amount != null && !hideAmounts"> · {{ record.currencyCode }} {{ record.amount }}</template></small><small v-if="record.memo">{{ record.memo }}</small><small>사진 {{ record.photoCount }}장</small><a v-if="record.thumbnailMediaId" :href="`/api/travel/media/${record.thumbnailMediaId}/content`" target="_blank" rel="noopener noreferrer"><img :src="`/api/travel/media/${record.thumbnailMediaId}/content?thumbnail=true`" alt="여행 사진 원본 보기" loading="lazy"></a><button type="button" @click="openTravelRecord(record)">여행 기록 원본 보기</button></article>
        </section>
        <section v-if="visible('routes')"><h3>이동 경로</h3><p v-if="dayErrors.routes" class="integrated-calendar__error">{{ dayErrors.routes }} <button @click="loadDay">재시도</button></p><p v-else-if="!daySources.routes">불러오는 중</p><p v-else-if="!daySources.routes.length">이동 경로가 없습니다.</p><article v-for="route in daySources.routes || []" :key="route.id" class="integrated-calendar__entry"><strong>{{ route.title }}</strong><span>{{ route.planName }}<template v-if="route.transportMode"> · {{ route.transportMode }}</template><template v-if="route.distanceKm != null"> · {{ route.distanceKm }}km</template><template v-if="route.durationMinutes != null"> · {{ route.durationMinutes }}분</template></span><button type="button" @click="emit('open-travel-source', { sourceType: 'route', planId: route.planId, routeId: route.id, routeDate: selectedDate })">경로 원본 보기</button></article></section>
        <section v-if="visible('events')"><h3>개인 일정</h3><p v-if="dayErrors.events" class="integrated-calendar__error">{{ dayErrors.events }} <button @click="loadDay">재시도</button></p><p v-else-if="!daySources.events">불러오는 중</p><p v-else-if="!dayEvents.length">일정이 없습니다.</p><article v-for="event in dayEvents" :key="event.id" class="integrated-calendar__entry"><strong>{{ event.title }}</strong><span>{{ event.startDate }} ~ {{ event.endDate }} · {{ event.allDay ? '종일' : `${event.startTime?.slice(0,5) || '시간 미지정'} ~ ${event.endTime?.slice(0,5) || '시간 미지정'}` }}</span><small v-if="event.memo">{{ event.memo }}</small><div><button type="button" @click="beginEvent(event)">수정</button> <button type="button" :disabled="eventBusy" @click="removeEvent(event)">삭제</button></div></article></section>
        <section v-if="includeShared && (visible('travel') || visible('routes')) && sharedDays.length"><h3>공유받은 여행 · 내 가계부 합계 제외</h3><article v-for="share in sharedDays" :key="`${share.type}-${share.shareId}`" class="integrated-calendar__entry"><strong>공유 · {{ share.title }}</strong><button type="button" :disabled="sharedSourceBusy" @click="openSharedTravelSource(share)">공유 여행 원본 보기</button>
          <template v-if="visible('travel')"><div v-for="record in share.records" :key="record.id"><strong>{{ record.title }}</strong> · {{ record.time ? record.time.slice(0,5) : '시간 미지정' }} · {{ record.kind === 'LEDGER' ? '여행 지출' : '기록' }} <span v-if="record.kind === 'LEDGER'">{{ currency(record.amountKrw) }}</span><p v-if="record.memo">{{ record.memo }}</p><img v-if="record.thumbnailMediaId" :src="`/api/travel/${share.type === 'exhibit' ? 'shared-exhibits' : 'record-shares'}/${share.shareId}/media/${record.thumbnailMediaId}/content?thumbnail=true`" alt="공유 여행 사진 미리보기" loading="lazy"></div></template>
          <template v-if="visible('routes')"><div v-for="route in share.routes" :key="route.id">경로 · {{ route.title }} · {{ route.transportMode }} · {{ route.distanceKm }}km</div></template>
        </article></section>
      </aside>
    </div>
    <section v-if="sharedSource" ref="sharedSourceRef" aria-label="공유 여행 원본"><button type="button" @click="sharedSource = null">공유 여행 원본 닫기</button><TravelSharedExhibitWorkspace embedded :selected-exhibit="sharedSource" /></section>
    <section v-if="mapSource" ref="mapSourceRef" aria-label="공유 지도 원본"><button type="button" @click="mapSource = null">공유 지도 원본 닫기</button><RecordMapWorkspace public-view :calendar-grant-id="mapSource.grantId" :place-focus-request="mapSource.focus" /></section>
  </section>
</template>

<style scoped>
.integrated-calendar { display: grid; gap: 1rem; min-width: 0; padding: clamp(.6rem, 1.2vw, 1.5rem); }
.integrated-calendar h1,.integrated-calendar h2,.integrated-calendar h3,.integrated-calendar p { margin: 0; }
.integrated-calendar button,.integrated-calendar input,.integrated-calendar select,.integrated-calendar textarea { font: inherit; color: inherit; background: var(--panel-bg, #fff); border: 1px solid var(--line, #9aaa91); border-radius: .3rem; min-height: 2.75rem; padding: .4rem .6rem; }
.integrated-calendar button { cursor: pointer; } .integrated-calendar button:focus-visible,.integrated-calendar input:focus-visible,.integrated-calendar select:focus-visible,.integrated-calendar textarea:focus-visible { outline: .2rem solid #159665; outline-offset: .1rem; }
.integrated-calendar__header,.integrated-calendar__details-head { display: flex; align-items: center; justify-content: space-between; gap: .8rem; flex-wrap: wrap; }
.integrated-calendar__header p { margin-top: .4rem; } .integrated-calendar__month-nav,.integrated-calendar__filters { display: flex; align-items: center; flex-wrap: wrap; gap: .5rem; }
.integrated-calendar__settings > summary { cursor: pointer; min-height: 2.75rem; padding: .65rem; border: 1px solid var(--line, #9aaa91); }
.integrated-calendar__settings > summary:focus-visible { outline: .2rem solid #159665; outline-offset: .1rem; }
.integrated-calendar__settings[open] > summary { margin-bottom: .5rem; }
.integrated-calendar__month-nav label,.integrated-calendar__filters label { display: flex; align-items: center; flex: 0 0 auto; gap: .3rem; min-height: 2.75rem; white-space: nowrap; }
.integrated-calendar__filters input[type='checkbox'],.integrated-calendar__form input[type='checkbox'],.integrated-calendar__undated input[type='checkbox'] { flex: none; width: 1.1rem; height: 1.1rem; min-height: 0; padding: 0; }
.integrated-calendar__undated label:has(input[type='checkbox']) { display: inline-flex; align-items: center; gap: .4rem; min-height: 2.75rem; }
.integrated-calendar__month-nav input { width: 6rem; }
.integrated-calendar__summary { display: flex; gap: .8rem 1.5rem; flex-wrap: wrap; padding: .8rem; border: 1px solid var(--line, #9aaa91); }
.integrated-calendar__layout { display: grid; grid-template-columns: minmax(0, 2fr) minmax(18rem, 1fr); align-items: start; gap: 1rem; }
.integrated-calendar__month,.integrated-calendar__details { min-width: 0; } .integrated-calendar__weekdays,.integrated-calendar__grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: .25rem; }
.integrated-calendar__weekdays span { padding: .4rem; } .integrated-calendar__cell { text-align: left; min-width: 0; height: clamp(6.5rem, 10vw, 9rem); display: flex; flex-direction: column; align-items: stretch; gap: .25rem; overflow: hidden; }
.integrated-calendar__cell.is-other { opacity: .55; } .integrated-calendar__cell.is-selected { border: .16rem solid #159665; } .integrated-calendar__cell-item { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: .75rem; }
.integrated-calendar__compact-text { display: none; }
.integrated-calendar__mobile-legend { display: none; }
.integrated-calendar__more { font-size: .72rem; font-weight: 700; } .integrated-calendar__details { display: grid; gap: .8rem; padding: 1rem; border: 1px solid var(--line, #9aaa91); }
.integrated-calendar__details section { display: grid; gap: .5rem; } .integrated-calendar__entry { display: grid; gap: .3rem; padding: .6rem; border: 1px solid var(--line, #9aaa91); overflow-wrap: anywhere; } .integrated-calendar__entry img { max-width: 6rem; max-height: 6rem; object-fit: cover; }
.integrated-calendar__form { display: grid; gap: .6rem; padding: .7rem; border: 1px solid var(--line, #9aaa91); } .integrated-calendar__form label { display: grid; gap: .2rem; } .integrated-calendar__error { padding: .5rem; border: 1px solid #bd3947; }
.integrated-calendar__form input,.integrated-calendar__form textarea { min-width: 0; max-width: 100%; }
.integrated-calendar__timeline { margin: 0; padding-left: 1.4rem; display: grid; gap: .35rem; }
.integrated-calendar__undated { padding: .7rem; border: 1px solid var(--line, #9aaa91); } .integrated-calendar__undated summary { cursor: pointer; font-weight: 700; } .integrated-calendar__undated section { margin-top: .7rem; }
.integrated-calendar__undated form { display: flex; flex-wrap: wrap; gap: .5rem; }
.integrated-calendar__undated form label { display: flex; flex: 1 1 15rem; flex-wrap: wrap; align-items: center; gap: .4rem; min-width: 0; }
.integrated-calendar__undated form input[type='text'] { flex: 1 1 12rem; min-width: 0; max-width: 100%; }
@media (max-width: 60rem) {
  .integrated-calendar__layout { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 35rem) { .integrated-calendar__summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .4rem 1rem; font-size: .75rem; } .integrated-calendar__summary > span { min-width: 0; overflow-wrap: anywhere; } .integrated-calendar__mobile-legend { display: block; margin-bottom: .4rem; font-size: .7rem; } .integrated-calendar__cell { height: 5.5rem; padding: .2rem; gap: .1rem; } .integrated-calendar__cell > strong small { display: none; } .integrated-calendar__cell-item,.integrated-calendar__more { flex: none; font-size: .6rem; line-height: 1.15; } .integrated-calendar__wide-text { display: none; } .integrated-calendar__compact-text { display: block; white-space: pre; letter-spacing: -.03em; } .integrated-calendar__weekdays span { padding: .2rem; } }
</style>
