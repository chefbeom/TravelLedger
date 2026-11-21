import { computed, reactive, ref, watch } from 'vue'
import {
  addCalendarMapLink, fetchCalendarDay, fetchCalendarEvents, fetchCalendarMapDay, fetchCalendarMapOptions, fetchCalendarMapSummary,
  fetchCalendarSharedDay, fetchCalendarSharedOptions, fetchCalendarSharedSummary, fetchCalendarSummary, fetchCalendarUndated,
  fetchCategories, fetchPaymentMethods, fetchRecordSharedTravel, fetchTravelPlans, fetchTravelSharedExhibit, requestRecordMap,
  setCalendarMapSelection, setCalendarSharedSelection,
} from './api.js'
import { SOURCE_LABELS } from './integratedCalendarView.js'

const MONTH_SOURCES = ['ledger', 'ledger-categories', 'places', 'travel', 'events']
const DAY_SOURCES = ['ledger', 'places', 'travel-records', 'routes', 'events']
const SHARED_ERROR_KEYS = ['options', 'summaries', 'day', 'fatal', 'action']

function createStore(names) {
  const blank = value => Object.fromEntries(names.map(name => [name, value]))
  return { names, data: reactive(blank(null)), errors: reactive(blank('')), pending: reactive(blank(false)), keys: {}, pendingKeys: {}, seq: Object.fromEntries(names.map(name => [name, 0])) }
}

// Stale-while-revalidate: existing data stays on screen while a request for the
// same or a newer key is running; the error text is only cleared on success.
async function revalidate(store, source, query, fetcher, { force = false, resetOnKeyChange = false, errorMessage }) {
  const key = JSON.stringify(query)
  if (!force && store.keys[source] === key && store.data[source] != null && !store.errors[source]) {
    // A request for another key may still be running; it is no longer wanted.
    if (store.pending[source] && store.pendingKeys[source] !== key) { store.seq[source] += 1; store.pending[source] = false }
    return
  }
  if (!force && store.pending[source] && store.pendingKeys[source] === key) return
  const seq = ++store.seq[source]
  if (store.keys[source] !== key && resetOnKeyChange) store.data[source] = null
  store.pending[source] = true; store.pendingKeys[source] = key
  try {
    const data = await fetcher(query)
    if (seq !== store.seq[source]) return
    store.data[source] = data; store.keys[source] = key; store.errors[source] = ''
  } catch {
    if (seq !== store.seq[source]) return
    store.data[source] = null; store.keys[source] = key; store.errors[source] = errorMessage
  } finally {
    if (seq === store.seq[source]) store.pending[source] = false
  }
}

async function fetchAllPages(fetchPage) {
  const items = []; let page = 0, totalPages = 1
  while (page < totalPages) {
    const result = await fetchPage(page)
    items.push(...(result.items || []))
    totalPages = Number(result.totalPages) || 0; page += 1
  }
  return items
}

/**
 * Loads every data source of the integrated calendar. It only does loading,
 * race protection and error state; derived data is computed elsewhere.
 */
export function useIntegratedCalendarSources({ grid, selectedDate, filters, includeShared, isVisible }) {
  const month = createStore(MONTH_SOURCES)
  const day = createStore(DAY_SOURCES)
  const sourceVisible = source => isVisible(source === 'ledger-categories' ? 'ledger' : source)

  // filter option lists
  const categories = ref([]), paymentMethods = ref([]), collections = ref([]), plans = ref([])
  const metadataErrors = ref([]), metadataLoading = ref(false)

  // undated items
  const undated = ref(null), undatedError = ref(''), undatedBusy = ref(false), undatedPlacePage = ref(0), undatedTripPage = ref(0)

  // shared trips
  const sharedOptions = ref([]), sharedSummaries = ref([]), sharedDays = ref([])
  const sharedErrors = reactive(Object.fromEntries(SHARED_ERROR_KEYS.map(key => [key, ''])))
  const sharedLoading = ref(false), sharedBusy = ref(false), sharedSourceBusy = ref(false)
  // shared maps
  const mapOptions = ref([]), mapSummaries = ref([]), mapDays = ref([])
  const mapErrors = reactive(Object.fromEntries(SHARED_ERROR_KEYS.map(key => [key, ''])))
  const mapLoading = ref(false), mapBusy = ref(false)

  const retrying = ref(false), actionError = ref('')
  const seqs = { sharedOptions: 0, sharedSummaries: 0, sharedDay: 0, mapOptions: 0, mapSummaries: 0, mapDay: 0, undated: 0 }
  let sharedDayLoadedDate = '', mapDayLoadedDate = ''
  const pendingDayKeys = { shared: '', map: '' }

  const monthParams = () => ({ from: grid.value.visibleStart, to: grid.value.visibleEnd })
  function monthQuery(source) {
    const g = grid.value, f = filters
    if (source === 'ledger') return { ...monthParams(), type: f.entryType, categoryId: f.categoryId, paymentMethodId: f.paymentMethodId }
    if (source === 'ledger-categories') return { from: g.monthStart, to: g.monthEnd, type: f.entryType, categoryId: f.categoryId, paymentMethodId: f.paymentMethodId }
    if (source === 'places') return { ...monthParams(), collectionId: f.collectionId, planId: f.planId }
    if (source === 'travel') return { ...monthParams(), planId: f.planId, collectionId: f.collectionId }
    return monthParams()
  }
  function dayQuery(source) {
    const date = selectedDate.value
    if (source === 'events') return { date }
    return { date, collectionId: source === 'places' ? filters.collectionId : '', planId: ['places', 'travel-records', 'routes'].includes(source) ? filters.planId : '' }
  }

  function loadMonth({ force = false, only = null } = {}) {
    return Promise.all((only || MONTH_SOURCES).map(source => revalidate(month, source, monthQuery(source), query => fetchCalendarSummary(source, query), { force, errorMessage: `${SOURCE_LABELS[source]} 월 정보를 불러오지 못했습니다.` })))
  }
  function loadDay({ force = false, only = null } = {}) {
    return Promise.all((only || DAY_SOURCES).map(source => revalidate(day, source, dayQuery(source), query => source === 'events'
      ? fetchCalendarEvents(query.date) : fetchCalendarDay(source, query.date, { collectionId: query.collectionId, planId: query.planId }),
    { force, resetOnKeyChange: true, errorMessage: `${SOURCE_LABELS[source]} 상세 정보를 불러오지 못했습니다.` })))
  }

  const anyPending = store => store.names.some(name => store.pending[name])
  const initialLoading = store => computed(() => store.names.some(name => sourceVisible(name) && store.data[name] == null && store.pending[name] && !store.errors[name]))
  const revalidating = store => computed(() => store.names.some(name => store.pending[name] && store.data[name] != null))
  const monthInitialLoading = initialLoading(month), dayInitialLoading = initialLoading(day)
  const monthRevalidating = revalidating(month), dayRevalidating = revalidating(day)
  const monthBusy = computed(() => anyPending(month)), dayBusy = computed(() => anyPending(day))

  // ---- shared trips -------------------------------------------------------
  async function loadSharedOptions() {
    const seq = ++seqs.sharedOptions
    sharedLoading.value = true
    try {
      const types = ['exhibit', 'record']
      const all = await Promise.allSettled(types.map(async type => (await fetchAllPages(page => fetchCalendarSharedOptions(type, page))).map(item => ({ ...item, type }))))
      if (seq !== seqs.sharedOptions) return
      sharedOptions.value = all.filter(item => item.status === 'fulfilled').flatMap(item => item.value)
      const failedTypes = all.flatMap((item, index) => item.status === 'rejected' ? [types[index] === 'exhibit' ? '공유 전시' : '공유 여행 기록'] : [])
      sharedErrors.options = failedTypes.length ? `${failedTypes.join('·')} 목록을 불러오지 못했습니다. 다른 공유 자료는 계속 표시됩니다.` : ''
      sharedErrors.fatal = ''
      await loadSharedSummaries()
    } catch {
      if (seq === seqs.sharedOptions) { sharedOptions.value = []; sharedSummaries.value = []; sharedDays.value = []; sharedErrors.fatal = '공유 자료를 불러오지 못했습니다.' }
    } finally { if (seq === seqs.sharedOptions) sharedLoading.value = false }
  }
  async function loadSharedSummaries() {
    const seq = ++seqs.sharedSummaries
    if (!includeShared.value) { seqs.sharedDay += 1; sharedSummaries.value = []; sharedDays.value = []; return }
    const selected = sharedOptions.value.filter(item => item.selected)
    const results = await Promise.allSettled(selected.map(item => fetchCalendarSharedSummary(item.type, item.id, { from: grid.value.visibleStart, to: grid.value.visibleEnd })))
    if (seq !== seqs.sharedSummaries) return
    // Two received shares can point at the same actual plan. Show it once,
    // retaining one currently authorized share for subsequent detail reads.
    sharedSummaries.value = [...new Map(results.filter(item => item.status === 'fulfilled').map(item => [item.value.planId, item.value])).values()]
    sharedErrors.summaries = results.some(item => item.status === 'rejected') ? '일부 공유 자료의 권한이 변경되었거나 조회에 실패했습니다. 다시 불러와 주세요.' : ''
    await loadSharedDay({ force: true })
  }
  async function loadSharedDay({ force = false } = {}) {
    if (!includeShared.value) { seqs.sharedDay += 1; sharedDays.value = []; return }
    const date = selectedDate.value
    const current = [...sharedSummaries.value]
    const key = `${date}|${current.map(item => `${item.type}:${item.shareId}`).join(',')}`
    if (!force && pendingDayKeys.shared === key) return
    const seq = ++seqs.sharedDay
    pendingDayKeys.shared = key
    if (sharedDayLoadedDate !== date) sharedDays.value = []
    const results = await Promise.allSettled(current.map(item => fetchCalendarSharedDay(item.type, item.shareId, date)))
    if (seq !== seqs.sharedDay) return
    pendingDayKeys.shared = ''
    sharedDayLoadedDate = date
    sharedDays.value = results.filter(item => item.status === 'fulfilled').map(item => item.value)
    if (results.some(item => item.status === 'rejected')) {
      const failed = new Set(results.flatMap((item, index) => item.status === 'rejected' ? [`${current[index].type}:${current[index].shareId}`] : []))
      sharedSummaries.value = sharedSummaries.value.filter(item => !failed.has(`${item.type}:${item.shareId}`))
      sharedErrors.day = '일부 공유 상세 정보의 권한이 변경되었거나 조회에 실패했습니다.'
    } else sharedErrors.day = ''
  }
  async function toggleShared(item, checked) {
    sharedBusy.value = true; sharedErrors.action = ''
    try { await setCalendarSharedSelection(item.type, item.id, checked); await loadSharedOptions(); return true }
    catch { sharedErrors.action = '공유 자료 선택을 저장하지 못했습니다. 권한을 확인하고 다시 시도해 주세요.'; return false }
    finally { sharedBusy.value = false }
  }
  async function openSharedTravelSource(share) {
    sharedSourceBusy.value = true; actionError.value = ''
    try {
      const detail = share.type === 'exhibit' ? await fetchTravelSharedExhibit(share.shareId) : await fetchRecordSharedTravel(share.shareId)
      // Permission and current selection may have changed while fetching.
      if (!includeShared.value || !sharedSummaries.value.some(item => item.type === share.type && item.shareId === share.shareId)) return null
      return detail
    } catch { actionError.value = '공유 여행 원본을 열 수 없습니다. 공유 권한을 확인해 주세요.'; return null }
    finally { sharedSourceBusy.value = false }
  }

  // ---- shared maps --------------------------------------------------------
  async function loadMapOptions() {
    const seq = ++seqs.mapOptions
    mapLoading.value = true
    try {
      const options = await fetchAllPages(page => fetchCalendarMapOptions(page))
      if (seq !== seqs.mapOptions) return
      mapOptions.value = options
      mapErrors.fatal = ''
      await loadMapSummaries()
    } catch {
      if (seq === seqs.mapOptions) { mapOptions.value = []; mapSummaries.value = []; mapDays.value = []; mapErrors.fatal = '공유 지도를 불러오지 못했습니다.' }
    } finally { if (seq === seqs.mapOptions) mapLoading.value = false }
  }
  async function loadMapSummaries() {
    const seq = ++seqs.mapSummaries
    if (!includeShared.value) { seqs.mapDay += 1; mapSummaries.value = []; mapDays.value = []; return }
    const results = await Promise.allSettled(mapOptions.value.filter(item => item.selected).map(item => fetchCalendarMapSummary(item.id, grid.value.visibleStart, grid.value.visibleEnd)))
    if (seq !== seqs.mapSummaries) return
    mapSummaries.value = results.filter(item => item.status === 'fulfilled').map(item => item.value)
    mapErrors.summaries = results.some(item => item.status === 'rejected') ? '일부 공유 지도 권한이 변경되었거나 조회에 실패했습니다.' : ''
    await loadMapDay({ force: true })
  }
  async function loadMapDay({ force = false } = {}) {
    if (!includeShared.value) { seqs.mapDay += 1; mapDays.value = []; return }
    const date = selectedDate.value
    const current = [...mapSummaries.value]
    const key = `${date}|${current.map(item => item.id).join(',')}`
    if (!force && pendingDayKeys.map === key) return
    const seq = ++seqs.mapDay
    pendingDayKeys.map = key
    if (mapDayLoadedDate !== date) mapDays.value = []
    const results = await Promise.allSettled(current.map(item => fetchCalendarMapDay(item.id, date)))
    if (seq !== seqs.mapDay) return
    pendingDayKeys.map = ''
    mapDayLoadedDate = date
    mapDays.value = results.filter(item => item.status === 'fulfilled').map(item => item.value)
    if (results.some(item => item.status === 'rejected')) {
      const failed = new Set(results.flatMap((item, index) => item.status === 'rejected' ? [current[index].id] : []))
      mapSummaries.value = mapSummaries.value.filter(item => !failed.has(item.id))
      mapErrors.day = '일부 공유 지도 권한이 변경되었거나 상세 조회에 실패했습니다.'
    } else mapErrors.day = ''
  }
  async function addSharedMap(link) {
    const value = String(link || '').trim()
    const token = /^[A-Za-z0-9_-]{43}$/.test(value) ? value : value.match(/#record-map-share\/([A-Za-z0-9_-]{43})$/)?.[1]
    if (!token) { mapErrors.action = '유효한 기록 지도 공유 주소를 입력해 주세요.'; return false }
    mapBusy.value = true; mapErrors.action = ''
    try { await addCalendarMapLink(token); await loadMapOptions(); return true }
    catch { mapErrors.action = '공유 지도를 추가하지 못했습니다. 링크가 만료되었거나 취소되었는지 확인해 주세요.'; return false }
    finally { mapBusy.value = false }
  }
  async function toggleSharedMap(item, checked) {
    mapBusy.value = true; mapErrors.action = ''
    try { await setCalendarMapSelection(item.id, checked); await loadMapOptions(); return true }
    catch { mapErrors.action = '공유 지도 선택을 저장하지 못했습니다.'; return false }
    finally { mapBusy.value = false }
  }
  const canOpenSharedMap = shareId => includeShared.value && mapSummaries.value.some(item => item.id === shareId)

  // ---- filter options and undated items -----------------------------------
  async function loadFilterOptions() {
    metadataLoading.value = true
    const results = await Promise.allSettled([fetchCategories(), fetchPaymentMethods(), requestRecordMap('/overview?page=0&size=1'), fetchTravelPlans()])
    const setters = [value => { categories.value = value || [] }, value => { paymentMethods.value = value || [] }, value => { collections.value = value?.collections || [] }, value => { plans.value = value || [] }]
    const labels = ['가계부 분류', '결제수단', '장소 분류', '여행']
    const failed = []
    results.forEach((result, index) => {
      if (result.status === 'fulfilled') setters[index](result.value)
      else failed.push(labels[index])
    })
    metadataErrors.value = failed
    metadataLoading.value = false
  }
  async function loadUndated() {
    const seq = ++seqs.undated
    undatedBusy.value = true; undatedError.value = ''
    try { const data = await fetchCalendarUndated({ placePage: undatedPlacePage.value, tripPage: undatedTripPage.value }); if (seq === seqs.undated) undated.value = data }
    catch { if (seq === seqs.undated) undatedError.value = '날짜 미지정 자료를 불러오지 못했습니다.' }
    finally { if (seq === seqs.undated) undatedBusy.value = false }
  }
  function changeUndatedPage(type, step) {
    if (type === 'places') undatedPlacePage.value = Math.max(0, undatedPlacePage.value + step)
    else undatedTripPage.value = Math.max(0, undatedTripPage.value + step)
    return loadUndated()
  }

  // ---- combined operations ------------------------------------------------
  async function refreshAll() {
    await Promise.all([loadMonth({ force: true }), loadDay({ force: true }), loadSharedOptions(), loadMapOptions(), ...(undated.value ? [loadUndated()] : [])])
  }
  async function refreshEvents() {
    await Promise.all([loadMonth({ force: true, only: ['events'] }), loadDay({ force: true, only: ['events'] })])
  }

  const issues = computed(() => [
    ...Object.entries(month.errors).filter(([source, message]) => message && sourceVisible(source)).map(([source, message]) => ({ key: `month-${source}`, message })),
    ...Object.entries(day.errors).filter(([source, message]) => message && sourceVisible(source)).map(([source, message]) => ({ key: `day-${source}`, message })),
    ...['options', 'summaries', 'day', 'fatal'].filter(key => sharedErrors[key]).map(key => ({ key: `shared-${key}`, message: sharedErrors[key] })),
    ...['options', 'summaries', 'day', 'fatal'].filter(key => mapErrors[key]).map(key => ({ key: `map-${key}`, message: mapErrors[key] })),
    ...(metadataErrors.value.length ? [{ key: 'metadata', message: `${metadataErrors.value.join('·')} 필터 목록을 불러오지 못했습니다. 다른 자료는 계속 표시됩니다.` }] : []),
    ...(actionError.value ? [{ key: 'action', message: actionError.value }] : []),
  ])

  async function retryFailed() {
    retrying.value = true
    const failedMonth = MONTH_SOURCES.filter(source => month.errors[source])
    const failedDay = DAY_SOURCES.filter(source => day.errors[source])
    const sharedFailed = ['options', 'summaries', 'day', 'fatal'].some(key => sharedErrors[key])
    const mapFailed = ['options', 'summaries', 'day', 'fatal'].some(key => mapErrors[key])
    actionError.value = ''
    try {
      await Promise.all([
        ...(failedMonth.length ? [loadMonth({ force: true, only: failedMonth })] : []),
        ...(failedDay.length ? [loadDay({ force: true, only: failedDay })] : []),
        ...(sharedFailed ? [loadSharedOptions()] : []), ...(mapFailed ? [loadMapOptions()] : []),
        ...(metadataErrors.value.length ? [loadFilterOptions()] : []),
      ])
    } finally { retrying.value = false }
  }

  // ---- triggers -----------------------------------------------------------
  watch(() => [grid.value.visibleStart, grid.value.visibleEnd, grid.value.monthStart], () => { loadMonth(); loadSharedSummaries(); loadMapSummaries() })
  watch(() => [filters.entryType, filters.categoryId, filters.paymentMethodId], () => { loadMonth() })
  watch(() => [filters.collectionId, filters.planId], () => { loadMonth(); loadDay() })
  watch(selectedDate, () => { loadDay(); loadSharedDay(); loadMapDay() })
  watch(includeShared, value => {
    if (value) {
      if (!sharedOptions.value.length && !sharedErrors.options) loadSharedOptions(); else loadSharedSummaries()
      if (!mapOptions.value.length && !mapErrors.fatal) loadMapOptions(); else loadMapSummaries()
    } else {
      seqs.sharedSummaries += 1; seqs.sharedDay += 1; seqs.mapSummaries += 1; seqs.mapDay += 1
      sharedSummaries.value = []; sharedDays.value = []; mapSummaries.value = []; mapDays.value = []
      pendingDayKeys.shared = ''; pendingDayKeys.map = ''
    }
  })

  function dispose() {
    for (const store of [month, day]) for (const name of store.names) store.seq[name] += 1
    for (const key of Object.keys(seqs)) seqs[key] += 1
  }

  return {
    monthSources: month.data, monthErrors: month.errors, monthPending: month.pending, monthInitialLoading, monthRevalidating, monthBusy,
    daySources: day.data, dayErrors: day.errors, dayPending: day.pending, dayInitialLoading, dayRevalidating, dayBusy,
    sharedOptions, sharedSummaries, sharedDays, sharedErrors, sharedLoading, sharedBusy, sharedSourceBusy,
    mapOptions, mapSummaries, mapDays, mapErrors, mapLoading, mapBusy,
    categories, paymentMethods, collections, plans, metadataErrors, metadataLoading,
    undated, undatedError, undatedBusy, undatedPlacePage, undatedTripPage,
    issues, retrying, actionError,
    loadMonth, loadDay, refreshAll, refreshEvents,
    loadSharedOptions, loadSharedSummaries, loadSharedDay, toggleShared,
    loadMapOptions, loadMapSummaries, loadMapDay, addSharedMap, toggleSharedMap,
    openSharedTravelSource, canOpenSharedMap,
    loadFilterOptions, loadUndated, changeUndatedPage, retryFailed, dispose,
  }
}
