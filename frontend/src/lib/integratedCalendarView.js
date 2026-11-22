import { calendarWeekday, overlapsCalendarDate } from './integratedCalendarCore.js'
import { ledgerEntryStatusLabel, summarizeLedgerAdjustments, splitAdjustmentLabel } from './ledgerSheetGroups.js'

export const LAYERS = ['ledger', 'places', 'travel', 'routes', 'events']
export const LAYER_LABELS = { ledger: '가계부', places: '장소·기록', travel: '여행', routes: '경로', events: '개인 일정' }
export const SOURCE_LABELS = { ledger: '가계부', 'ledger-categories': '가계부 분류', places: '장소·기록', travel: '여행', events: '개인 일정', 'travel-records': '여행 기록', routes: '이동 경로' }

const WEEKDAY_SHORT = ['일', '월', '화', '수', '목', '금', '토']
const MINUS = '−'

export function isLayerVisible(layers, source) {
  return source === 'travel-records' ? layers.travel : source === 'ledger-categories' ? layers.ledger : Boolean(layers[source])
}

export function periodLabel(kind, start, end, date) {
  if (start === end) return kind
  return `${kind} ${date === start ? '시작' : date === end ? '종료' : '기간'}`
}

function periodRole(start, end, date) {
  return start === end ? 'single' : date === start ? 'start' : date === end ? 'end' : 'middle'
}

export function ledgerStatus(entry) {
  return ledgerEntryStatusLabel(entry)
}

// ---- formatters -----------------------------------------------------------

export function formatDayHeading(iso, todayIso) {
  const [year, month, day] = iso.split('-').map(Number)
  const showYear = todayIso && Number(todayIso.slice(0, 4)) !== year
  return `${showYear ? `${year}년 ` : ''}${month}월 ${day}일 ${WEEKDAY_SHORT[calendarWeekday(iso)]}요일`
}

export function formatMonthDay(iso) {
  const [, month, day] = iso.split('-').map(Number)
  return `${month}월 ${day}일(${WEEKDAY_SHORT[calendarWeekday(iso)]})`
}

function shortMonthDay(iso) {
  const [, month, day] = iso.split('-').map(Number)
  return `${month}월 ${day}일`
}

const compactFormatter = new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 })
const plainFormatter = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 })

// Signed amount for a calendar cell. Amounts of one million and above are
// always abbreviated so they fit in a cell.
export function formatCellAmount(value, { compact = false, hidden = false } = {}) {
  if (hidden) return '•••'
  const number = Number(value) || 0
  const abs = Math.abs(number)
  const sign = number < 0 ? MINUS : number > 0 ? '+' : ''
  return `${sign}${compact || abs >= 1000000 ? compactFormatter.format(abs) : plainFormatter.format(abs)}`
}

export function signedAmountText(sign, text) {
  return `${sign && /\d/.test(text) ? sign : ''}${text}`
}

// ---- month data -----------------------------------------------------------

function firstByDate(list) {
  const map = new Map()
  for (const item of list || []) if (!map.has(item.date)) map.set(item.date, item)
  return map
}

function groupByDate(list) {
  const map = new Map()
  for (const item of list || []) {
    if (!map.has(item.date)) map.set(item.date, [])
    map.get(item.date).push(item)
  }
  return map
}

export function buildDaySummaries({ days, month, sharedSummaries, mapSummaries, isVisible, includeShared, formatCurrency }) {
  const m = month || {}
  const ledgerByDate = firstByDate(m.ledger)
  const placesByDate = firstByDate(m.places)
  const routesByDate = firstByDate(m.travel?.routes)
  const recordsByDate = groupByDate(m.travel?.records)
  const mapCounts = (mapSummaries || []).map(share => ({ share, byDate: firstByDate(share.days) }))
  const shared = (sharedSummaries || []).map(share => ({ share, records: groupByDate(share.data?.records), routes: firstByDate(share.data?.routes) }))
  const placesVisible = isVisible('places'), travelVisible = isVisible('travel')
  const result = new Map()
  for (const { date } of days) {
    const items = []
    const counts = [], spans = []
    const pushCount = (key, layer, isShared, count, text) => {
      const entry = { key, layer, shared: isShared, count, text }
      counts.push(entry); items.push({ ...entry, kind: 'count' })
    }
    const pushSpan = (key, layer, isShared, title, start, end, text) => {
      const entry = { key, layer, shared: isShared, title, role: periodRole(start, end, date), text }
      spans.push(entry); items.push({ ...entry, kind: 'span' })
    }
    let ledger = null
    const money = ledgerByDate.get(date)
    if (isVisible('ledger') && money) {
      ledger = { income: Number(money.income) || 0, expense: Number(money.expense) || 0, transactionCount: Number(money.transactionCount) || 0 }
      items.push({ key: 'ledger', kind: 'money', layer: 'ledger', shared: false, text: `수입 ${formatCurrency(money.income)} · 지출 ${formatCurrency(money.expense)}`, ...ledger })
    }
    const visits = placesByDate.get(date)
    if (placesVisible && visits?.count) pushCount('places', 'places', false, visits.count, `장소 기록 ${visits.count}건`)
    if (includeShared && placesVisible) for (const { share, byDate } of mapCounts) {
      const count = byDate.get(date)?.count
      if (count) pushCount(`shared-map-${share.id}`, 'places', true, count, `공유 장소 ${count}건`)
    }
    if (travelVisible) for (const period of m.travel?.periods || []) if (overlapsCalendarDate(period.startDate, period.endDate, date)) {
      pushSpan(`travel-${period.id}`, 'travel', false, period.title, period.startDate, period.endDate, `${periodLabel('여행', period.startDate, period.endDate, date)} · ${period.title}`)
    }
    if (travelVisible) for (const item of recordsByDate.get(date) || []) {
      const count = item.count - (placesVisible && m.places ? (item.importedCount || 0) : 0)
      if (count > 0) pushCount(`record-${item.type}`, 'travel', false, count, `여행 기록 ${count}건`)
    }
    const route = routesByDate.get(date)
    if (isVisible('routes') && route?.count) pushCount('routes', 'routes', false, route.count, `경로 ${route.count}건`)
    if (isVisible('events')) for (const event of m.events || []) if (overlapsCalendarDate(event.startDate, event.endDate, date)) {
      pushSpan(`event-${event.id}`, 'events', false, event.title, event.startDate, event.endDate, `${periodLabel('일정', event.startDate, event.endDate, date)} · ${event.title}`)
    }
    if (includeShared && travelVisible) for (const { share, records } of shared) {
      for (const period of share.data?.periods || []) if (overlapsCalendarDate(period.startDate, period.endDate, date)) {
        pushSpan(`shared-${share.type}-${share.shareId}-${period.id}`, 'travel', true, period.title, period.startDate, period.endDate, `${periodLabel('공유 여행', period.startDate, period.endDate, date)} · ${period.title}`)
      }
      for (const record of records.get(date) || []) pushCount(`shared-record-${share.type}-${share.shareId}-${record.type}`, 'travel', true, record.count, `공유 기록 ${record.count}건`)
    }
    if (includeShared && isVisible('routes')) for (const { share, routes } of shared) {
      const sharedRoute = routes.get(date)
      if (sharedRoute?.count) pushCount(`shared-route-${share.type}-${share.shareId}`, 'routes', true, sharedRoute.count, `공유 경로 ${sharedRoute.count}건`)
    }
    result.set(date, { date, ledger, counts, spans, items, total: items.length })
  }
  return result
}

export function buildMonthSummary({ grid, month, isVisible, includeShared, shared }) {
  const m = month || {}
  const inMonth = item => item.date >= grid.monthStart && item.date <= grid.monthEnd
  const ledgerDays = (m.ledger || []).filter(inMonth)
  const placeDays = (m.places || []).filter(inMonth)
  const income = ledgerDays.reduce((sum, item) => sum + (Number(item.income) || 0), 0)
  const expense = ledgerDays.reduce((sum, item) => sum + (Number(item.expense) || 0), 0)
  const categories = m['ledger-categories']
  return {
    income, expense, balance: income - expense,
    visits: placeDays.reduce((sum, item) => sum + item.count, 0),
    busiestExpenseDay: ledgerDays.filter(item => Number(item.expense) > 0).reduce((best, item) => !best || Number(item.expense) > Number(best.expense) ? item : best, null),
    busiestVisitDay: placeDays.filter(item => item.count > 0).reduce((best, item) => !best || item.count > best.count ? item : best, null),
    topCategory: isVisible('ledger') && categories?.length ? categories[0] : null,
    tripCount: m.travel ? m.travel.periods.filter(period => period.startDate <= grid.monthEnd && period.endDate >= grid.monthStart).length : 0,
    sharedCount: includeShared && isVisible('travel') ? (shared || []).length : 0,
  }
}

export function collectMonthSpans({ month, sharedSummaries, isVisible, includeShared }) {
  const spans = []
  if (isVisible('travel')) for (const period of month?.travel?.periods || []) spans.push({ key: `travel-${period.id}`, layer: 'travel', shared: false, title: period.title, startDate: period.startDate, endDate: period.endDate })
  if (isVisible('events')) for (const event of month?.events || []) spans.push({ key: `event-${event.id}`, layer: 'events', shared: false, title: event.title, startDate: event.startDate, endDate: event.endDate })
  if (includeShared && isVisible('travel')) for (const share of sharedSummaries || []) for (const period of share.data?.periods || []) {
    spans.push({ key: `shared-${share.type}-${share.shareId}-${period.id}`, layer: 'travel', shared: true, title: period.title, startDate: period.startDate, endDate: period.endDate })
  }
  return spans
}

// ---- day timeline ---------------------------------------------------------

export function visibleSharedMapEntries(share, { travelVisible, sharedDays }) {
  return share.entries.filter(item => item.sourceKind !== 'TRAVEL_IMPORT' || !travelVisible || !(sharedDays || []).some(trip => trip.records.some(record => record.id === item.id)))
}

function ledgerTime(row) {
  return row.parts.map(item => item.entryTime).filter(Boolean).sort()[0] || ''
}

const GROUP_ORDER = { allday: 0, timed: 1, untimed: 2 }
const UNTIMED = '시간 미지정'

function timedFields(time) {
  return time ? { group: 'timed', time: String(time), timeLabel: String(time).slice(0, 5) } : { group: 'untimed', time: '', timeLabel: UNTIMED }
}

export function buildDayTimeline({ date, isVisible, includeShared, hideAmounts = false, ledgerRows, daySources, periods, sharedSummaries, sharedDays, mapDays, formatCurrency }) {
  const rows = []
  const src = daySources || {}
  const base = { timeNote: '', badges: [], amount: null, secondary: [], memo: '', thumb: null, subtitle: '' }
  const add = row => rows.push({ ...base, ...row })
  const amountOf = (value, sign, tone) => ({ value: Number(value) || 0, sign, tone, text: signedAmountText(sign, formatCurrency(value)) })

  const placesVisible = isVisible('places'), travelVisible = isVisible('travel')
  const importedRecordIds = new Set((src.places || []).filter(item => item.sourceKind === 'TRAVEL_IMPORT').map(item => item.sourceTravelRecordId))
  const travelRecordsById = new Map((src['travel-records'] || []).map(item => [item.id, item]))
  const displayedTravelRecords = (src['travel-records'] || []).filter(item => !placesVisible || !importedRecordIds.has(item.id))
  const linkedImportedRecord = place => place.sourceKind === 'TRAVEL_IMPORT' ? travelRecordsById.get(place.sourceTravelRecordId) : null
  const travelRecordAction = record => ({ type: 'travel', payload: { sourceType: record.type === 'MEMORY' ? 'memory' : 'ledger', planId: record.planId, recordId: record.id } })
  const sharedLabel = '공유'

  if (isVisible('ledger') && ledgerRows) for (const row of ledgerRows) {
    const first = row.parts[0]
    const time = ledgerTime(row)
    const badges = []
    if (row.parts.some(part => part.financiallyExcluded)) {
      badges.push(row.isSplit ? `일부 수단 합계 제외 · 집계 금액 ${formatCurrency(row.parts.filter(part => !part.financiallyExcluded).reduce((sum, part) => sum + Number(part.amount || 0), 0))}` : '합계 제외')
    }
    if (row.kind === 'adjustment') {
      badges.push(`${ledgerEntryStatusLabel(first)} · 원거래 #${first.adjustmentOriginId}`)
    } else {
      const summary = row.isSplit ? null : summarizeLedgerAdjustments(first, row.adjustments)
      const status = row.isSplit ? splitAdjustmentLabel(row) : summary.label
      if (status) badges.push(status)
      if (summary && summary.activeRefundReceived > 0) badges.push(`환불 입금 +${formatCurrency(summary.activeRefundReceived)}`)
    }
    if (first.travelRecordId) badges.push('여행 지출과 연결된 가계부 거래')
    const income = first.entryType === 'INCOME'
    const notes = []
    if (time.startsWith('00:00')) notes.push('가계부의 00:00은 저장된 값이며 미입력 여부를 구분할 수 없습니다.')
    if (time.startsWith('00:00') && row.isSplit) notes.push('여러 결제수단 중 가장 이른 저장 시간입니다.')
    const method = row.isSplit ? `${first.paymentMethodName || '결제수단'} 외 ${row.paymentMethodCount - 1}` : first.paymentMethodName || ''
    add({
      key: row.key, kind: 'ledger', layer: 'ledger', shared: false, ...timedFields(time), timeNote: notes.join(' '),
      title: first.title,
      subtitle: [income ? '수입' : '지출', method].filter(Boolean).join(' · '),
      badges, amount: { ...amountOf(row.displayedAmount, income ? '+' : MINUS, income ? 'income' : 'expense'), struck: row.parts.every(part => part.financiallyExcluded) },
      primary: { label: '가계부 원본 보기', action: { type: 'ledger', payload: { date, entryId: first.id } } },
      secondary: first.travelPlanId && first.travelRecordId ? [{ label: '연결된 여행 지출 보기', action: { type: 'travel', payload: { sourceType: 'ledger', planId: first.travelPlanId, recordId: first.travelRecordId } } }] : [],
      data: row,
    })
  }
  if (placesVisible) for (const place of src.places || []) {
    const linked = linkedImportedRecord(place)
    const badges = []
    if (place.sourceKind === 'TRAVEL_IMPORT') badges.push(`여행에서 가져온 기록${linked ? ` · ${linked.planName}` : ''}`)
    let amount = null
    if (linked?.type === 'LEDGER') { badges.push(linked.linkedToLedger ? '가계부 연결' : '여행 지출 · 가계부 미반영'); amount = amountOf(linked.amountKrw, '', 'neutral') }
    if (Number(place.photoCount) >= 1) badges.push(`사진 ${place.photoCount}장`)
    add({
      key: `place-${place.sourceKind}-${place.id}`, kind: 'place', layer: 'places', shared: false, ...timedFields(place.time),
      title: `${place.placeName} · ${place.title}`, subtitle: (place.categories || []).join(', ') || '분류 없음', badges, amount, memo: place.memo || '',
      thumb: place.thumbnailPhotoId ? { src: `/api/record-map/photos/${place.thumbnailPhotoId}/content?thumbnail=true`, alt: `${place.placeName} 사진` }
        : place.thumbnailMediaId ? { src: `/api/travel/media/${place.thumbnailMediaId}/content?thumbnail=true`, alt: `${place.placeName} 사진` } : null,
      primary: { label: '장소 원본 보기', action: { type: 'place', payload: { placeId: place.placeId, visitId: place.sourceKind === 'VISIT' ? place.id : null } } },
      secondary: linked ? [{ label: '여행 기록 원본 보기', action: travelRecordAction(linked) }] : [], data: place,
    })
  }
  if (travelVisible) {
    for (const period of periods || []) add({
      key: `period-${period.id}`, kind: 'travel-period', layer: 'travel', shared: false, group: 'allday', time: '', timeLabel: '기간',
      title: period.title, subtitle: `${shortMonthDay(period.startDate)} ~ ${shortMonthDay(period.endDate)} · ${period.status === 'COMPLETED' ? '완료' : period.status === 'PLANNED' ? '계획' : period.status}`,
      primary: { label: '여행 원본 보기', action: { type: 'travel', payload: { sourceType: 'plan', planId: period.id } } }, data: period,
    })
    for (const record of displayedTravelRecords) {
      const subtitle = [record.planName, record.type === 'LEDGER' ? (record.linkedToLedger ? '가계부 연결' : '여행 지출 · 가계부 미반영') : '여행 기록', record.placeName].filter(Boolean).join(' · ')
      const foreign = record.type === 'LEDGER' && record.currencyCode && record.currencyCode !== 'KRW' && record.amount != null && !hideAmounts ? `${record.currencyCode} ${record.amount}` : ''
      add({
        key: `travel-${record.id}`, kind: 'travel-record', layer: 'travel', shared: false, ...timedFields(record.time),
        title: record.title, subtitle: [subtitle, foreign].filter(Boolean).join(' · '), memo: record.memo || '',
        badges: Number(record.photoCount) >= 1 ? [`사진 ${record.photoCount}장`] : [],
        amount: record.type === 'LEDGER' ? amountOf(record.amountKrw, '', 'neutral') : null,
        thumb: record.thumbnailMediaId ? { src: `/api/travel/media/${record.thumbnailMediaId}/content?thumbnail=true`, href: `/api/travel/media/${record.thumbnailMediaId}/content`, alt: `${record.title} 사진` } : null,
        primary: { label: '여행 기록 원본 보기', action: travelRecordAction(record) }, data: record,
      })
    }
  }
  if (isVisible('routes')) for (const route of src.routes || []) {
    const subtitle = [route.planName, route.transportMode, route.distanceKm != null ? `${route.distanceKm}km` : '', route.durationMinutes != null ? `${route.durationMinutes}분` : ''].filter(value => value !== '' && value != null).join(' · ')
    add({
      key: `route-${route.id}`, kind: 'route', layer: 'routes', shared: false, group: 'untimed', time: '', timeLabel: UNTIMED, title: route.title, subtitle,
      primary: { label: '경로 원본 보기', action: { type: 'travel', payload: { sourceType: 'route', planId: route.planId, routeId: route.id, routeDate: date } } }, data: route,
    })
  }
  if (isVisible('events')) for (const event of src.events || []) {
    const ending = event.startDate !== date && event.endDate === date
    const starting = event.startDate === date
    let fields
    if (event.allDay) fields = { group: 'allday', time: '', timeLabel: '종일' }
    else if (ending) fields = event.endTime ? { group: 'timed', time: event.endTime, timeLabel: `종료 ${String(event.endTime).slice(0, 5)}` } : { group: 'untimed', time: '', timeLabel: '종료' }
    else if (starting) fields = timedFields(event.startTime)
    else fields = { group: 'allday', time: '', timeLabel: '계속' }
    const range = event.startDate === event.endDate ? shortMonthDay(event.startDate) : `${shortMonthDay(event.startDate)} ~ ${shortMonthDay(event.endDate)}`
    const clock = event.allDay ? '' : `${event.startTime?.slice(0, 5) || UNTIMED} ~ ${event.endTime?.slice(0, 5) || UNTIMED}`
    add({
      key: `event-${event.id}`, kind: 'event', layer: 'events', shared: false, ...fields,
      title: event.title, subtitle: [range, clock].filter(Boolean).join(' · '), memo: event.memo || '',
      primary: { label: '일정 수정', action: { type: 'event-edit', payload: event } },
      secondary: [{ label: '수정', action: { type: 'event-edit', payload: event } }, { label: '삭제', action: { type: 'event-delete', payload: event } }], data: event,
    })
  }
  if (includeShared && travelVisible) {
    for (const share of sharedSummaries || []) for (const period of share.data?.periods || []) if (overlapsCalendarDate(period.startDate, period.endDate, date)) add({
      key: `shared-period-${share.type}-${share.shareId}-${period.id}`, kind: 'shared-period', layer: 'travel', shared: true, group: 'allday', time: '', timeLabel: '기간',
      title: period.title, subtitle: `공유 · ${share.title}`, badges: [sharedLabel],
      primary: { label: '공유 여행 원본 보기', action: { type: 'shared-travel', payload: { type: share.type, shareId: share.shareId } } }, data: period,
    })
    for (const share of sharedDays || []) for (const record of share.records || []) add({
      key: `shared-${share.type}-${share.shareId}-${record.id}`, kind: 'shared-record', layer: 'travel', shared: true, ...timedFields(record.time),
      title: record.title, subtitle: `공유 · ${share.title} · ${record.kind === 'LEDGER' ? '여행 지출' : '기록'}`, memo: record.memo || '',
      badges: record.kind === 'LEDGER' ? [sharedLabel, '합계 제외'] : [sharedLabel],
      amount: record.kind === 'LEDGER' ? amountOf(record.amountKrw, '', 'neutral') : null,
      thumb: record.thumbnailMediaId ? { src: `/api/travel/${share.type === 'exhibit' ? 'shared-exhibits' : 'record-shares'}/${share.shareId}/media/${record.thumbnailMediaId}/content?thumbnail=true`, alt: `${record.title} 사진` } : null,
      primary: { label: '공유 여행 원본 보기', action: { type: 'shared-travel', payload: { type: share.type, shareId: share.shareId } } }, data: record,
    })
  }
  if (includeShared && isVisible('routes')) for (const share of sharedDays || []) for (const route of share.routes || []) add({
    key: `shared-route-${share.type}-${share.shareId}-${route.id}`, kind: 'shared-route', layer: 'routes', shared: true, group: 'untimed', time: '', timeLabel: UNTIMED,
    title: route.title, subtitle: [route.transportMode, route.distanceKm != null ? `${route.distanceKm}km` : ''].filter(Boolean).join(' · '), badges: [sharedLabel],
    primary: { label: '공유 여행 원본 보기', action: { type: 'shared-travel', payload: { type: share.type, shareId: share.shareId } } }, data: route,
  })
  if (includeShared && placesVisible) for (const share of mapDays || []) for (const item of visibleSharedMapEntries(share, { travelVisible, sharedDays })) {
    const kindPath = item.sourceKind === 'VISIT' ? 'visit' : 'travel'
    add({
      key: `shared-map-${share.id}-${item.sourceKind}-${item.id}`, kind: 'shared-map', layer: 'places', shared: true, ...timedFields(item.time),
      title: `${item.placeName} · ${item.title}`, subtitle: `공유 · ${share.title}`, memo: item.memo || '',
      badges: Number(item.photoCount) >= 1 ? [sharedLabel, `사진 ${item.photoCount}장`] : [sharedLabel],
      thumb: item.thumbnailId ? { src: `/api/integrated-calendar/shared-map/${share.id}/photos/${kindPath}/${item.thumbnailId}?thumbnail=true`, href: `/api/integrated-calendar/shared-map/${share.id}/photos/${kindPath}/${item.thumbnailId}`, alt: `${item.placeName} 공유 사진` } : null,
      primary: { label: '공유 장소 원본 보기', action: { type: 'shared-map', payload: { shareId: share.id, item } } }, data: item,
    })
  }
  return rows.sort((a, b) => GROUP_ORDER[a.group] - GROUP_ORDER[b.group] || String(a.time || '').localeCompare(String(b.time || '')) || a.key.localeCompare(b.key))
}
