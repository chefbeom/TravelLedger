<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import RecordMapDialog from '../RecordMapDialog.vue'
import TravelMapPanel from '../TravelMapPanel.vue'
import TravelImportDraftItem from './TravelImportDraftItem.vue'
import { useTravelImport } from '../../lib/useTravelImport'
import { groupDraftsByDate } from '../../lib/travelImport'

const LEAVE_GUARD_EVENT = 'calen-route-leave-guard'
const LEAVE_MESSAGE = '저장하지 않은 가져오기 항목이 있습니다. 나갈까요?'

const props = defineProps({
  open: { type: Boolean, default: false },
  plans: { type: Array, default: () => [] },
  planId: { type: [String, Number], default: '' },
})
const emit = defineEmits(['close', 'saved', 'open-route-editor'])

const imp = reactive(useTravelImport())
const query = ref('')
const dragging = ref(false)
const mapReady = ref(false)
const mapColumn = ref(null)

const groups = computed(() => groupDraftsByDate(imp.drafts, imp.planStartDate))
const selectValue = computed(() => (imp.target.mode === 'new' ? '__new__' : imp.target.planId))
const busy = computed(() => imp.saveState === 'saving')
const picking = computed(() => imp.pickArmed || Boolean(imp.pickTargetId))
const canRetry = computed(() => imp.saveState === 'partial')
const needsPlanChoice = computed(() => imp.target.mode === 'existing' && !imp.target.planId && !imp.target.createdPlanId)
const hasFiles = computed(() => imp.drafts.length > 0)
const planLocked = computed(() => Boolean(imp.target.createdPlanId))
const summaryText = computed(() => {
  const s = imp.summary
  const parts = [`기록 ${s.records}`, `사진 ${s.photos}`, `경로 ${s.routes}`]
  if (s.excluded) parts.push(`제외 ${s.excluded}`)
  if (s.needsReview) parts.push(`확인 필요 ${s.needsReview}`)
  if (s.saved) parts.push(`저장됨 ${s.saved}`)
  if (s.failed) parts.push(`실패 ${s.failed}`)
  return parts.join(' · ')
})

watch(() => props.open, (value) => {
  if (value) imp.reset({ planId: props.planId, plans: props.plans })
}, { immediate: true })

onMounted(() => {
  // The native <dialog> is shown on the next tick; mount Leaflet one frame later so it can measure itself.
  requestAnimationFrame(() => requestAnimationFrame(() => {
    mapReady.value = true
    imp.fitKey += 1
  }))
})

watch(() => imp.hasUnsaved, (active) => {
  window.dispatchEvent(new CustomEvent(LEAVE_GUARD_EVENT, { detail: { active, message: LEAVE_MESSAGE } }))
}, { immediate: true })

function onBeforeUnload(event) {
  if (!imp.hasUnsaved) return
  event.preventDefault()
  event.returnValue = ''
}
window.addEventListener('beforeunload', onBeforeUnload)

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
  window.dispatchEvent(new CustomEvent(LEAVE_GUARD_EVENT, { detail: { active: false } }))
  imp.dispose()
})

function selectedPlanName() {
  if (imp.target.mode === 'new') return imp.target.name.trim()
  const plan = props.plans.find((item) => String(item.id) === String(imp.target.planId))
  return plan?.name || ''
}

function emitSaved(result) {
  const createdPlan = imp.target.mode === 'new' && Boolean(imp.target.createdPlanId)
  emit('saved', {
    planId: result?.planId || imp.target.createdPlanId || imp.target.planId,
    records: result?.counts?.records ?? imp.summary.savedRecords,
    photos: result?.counts?.photos ?? imp.summary.savedPhotos,
    routes: result?.counts?.routes ?? imp.summary.savedRoutes,
    planName: selectedPlanName(),
    createdPlan,
  })
}

async function onSave() {
  const result = await imp.save()
  if (result.ok) emitSaved(result)
}

function requestClose() {
  if (busy.value) return
  const pending = imp.drafts.filter((draft) => draft.include && draft.status !== 'saved').length
  const savedAny = imp.summary.saved > 0 || Boolean(imp.target.createdPlanId)
  if (pending) {
    const message = savedAny
      ? `저장된 항목은 유지되고, 저장하지 못한 ${pending}건은 버려집니다. 닫을까요?`
      : `저장하지 않은 항목 ${pending}건이 있습니다. 닫을까요?`
    if (!window.confirm(message)) return
  }
  if (savedAny) emitSaved(null)
  else emit('close')
}

function openRouteEditor() {
  const planId = imp.target.createdPlanId || imp.target.planId
  const pending = imp.drafts.filter((draft) => draft.include && draft.status !== 'saved').length
  if (pending && !window.confirm(`저장하지 않은 항목 ${pending}건이 있습니다. 경로 편집 화면으로 이동할까요?`)) return
  emit('open-route-editor', { planId })
}

function onFiles(event) {
  const input = event.target
  imp.addFiles(input.files)
  input.value = ''
}

function onDrop(event) {
  dragging.value = false
  if (busy.value) return
  imp.addFiles(event.dataTransfer?.files)
}

async function scrollMapIntoView() {
  await nextTick()
  mapColumn.value?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
}

function armPick() {
  imp.pickTargetId = ''
  imp.pickArmed = !imp.pickArmed
  if (imp.pickArmed) scrollMapIntoView()
}

function cancelPick() {
  imp.pickArmed = false
  imp.pickTargetId = ''
}

function locate(draft) {
  imp.pickArmed = false
  imp.pickTargetId = draft.id
  imp.focusedId = draft.id
  scrollMapIntoView()
}

function showOnMap(draft) {
  imp.focusedId = draft.id
  scrollMapIntoView()
}

function onPick(point) {
  if (imp.pickTargetId) {
    const id = imp.pickTargetId
    imp.pickTargetId = ''
    imp.setDraftLocation(id, point, 'map')
    return
  }
  if (imp.pickArmed) {
    imp.pickArmed = false
    imp.addPlace(point, 'map')
  }
}

function onMoveMarker({ marker, point }) {
  imp.setDraftLocation(marker.id, point, 'map')
}

function onSelectMarker(marker) {
  imp.focusedId = marker.id
}

function chooseResult(result) {
  imp.addPlace({
    latitude: result.latitude,
    longitude: result.longitude,
    name: result.name,
    country: result.country,
    region: result.region,
  }, 'search')
  imp.searchResults = []
  imp.searchMessage = ''
  query.value = ''
}
</script>

<template>
  <RecordMapDialog
    :open="open"
    wide
    title="여행에 추가"
    :busy="busy"
    class="travel-import-dialog"
    @close="requestClose"
  >
    <div class="travel-import-body" @dragover.prevent @drop.prevent>
      <p v-if="imp.error" class="feedback feedback--error" role="alert">{{ imp.error }}</p>

      <section class="travel-import-target">
        <label class="field travel-import-target__plan">
          <span class="field__label">저장할 여행</span>
          <select
            aria-label="저장할 여행"
            :value="selectValue"
            :disabled="planLocked"
            @change="imp.setTargetPlan($event.target.value)"
          >
            <option value="" disabled>여행을 고르세요</option>
            <option v-for="plan in plans" :key="plan.id" :value="String(plan.id)">{{ plan.name }}{{ !plan.startDate && !plan.endDate ? ' · 기간 없음' : '' }}</option>
            <option value="__new__">새 여행 만들기</option>
          </select>
        </label>
        <template v-if="imp.target.mode === 'new'">
          <label class="field">
            <span class="field__label">새 여행 이름</span>
            <input
              type="text"
              aria-label="새 여행 이름"
              maxlength="120"
              :value="imp.target.name"
              :disabled="planLocked"
              @input="imp.updateTarget({ name: $event.target.value })"
            />
          </label>
          <div class="travel-import-target__period">
            <label v-if="!imp.target.noPeriod" class="field">
              <span class="field__label">시작일</span>
              <input type="date" aria-label="새 여행 시작일" :value="imp.target.startDate" :disabled="planLocked" @change="imp.updateTarget({ startDate: $event.target.value })" />
            </label>
            <label v-if="!imp.target.noPeriod" class="field">
              <span class="field__label">종료일</span>
              <input type="date" aria-label="새 여행 종료일" :value="imp.target.endDate" :disabled="planLocked" @change="imp.updateTarget({ endDate: $event.target.value })" />
            </label>
            <label class="travel-import-target__check">
              <input type="checkbox" aria-label="가져온 여행 기간 없음" :checked="imp.target.noPeriod" :disabled="planLocked" @change="imp.updateTarget({ noPeriod: $event.target.checked })" />
              <span>기간 없음 <small>(기간은 사진·GPX 날짜로 추정합니다)</small></span>
            </label>
          </div>
        </template>
        <p v-else-if="needsPlanChoice" class="travel-import-hint">저장할 여행을 고르세요.</p>
      </section>

      <div class="travel-import-grid">
        <div ref="mapColumn" class="travel-import-mapcol">
          <p v-if="picking" class="travel-import-pickbar" role="status">
            <span>지도를 눌러 위치를 정하세요.</span>
            <button type="button" class="button button--ghost" @click="cancelPick">취소</button>
          </p>
          <div class="travel-import-mapbox">
            <TravelMapPanel
              v-if="mapReady"
              class="travel-import-map"
              :markers="imp.mapMarkers"
              :routes="imp.mapRoutes"
              :enable-pick-location="picking"
              :draggable-markers="!busy"
              :selected-marker-id="imp.focusedId || null"
              :preserve-selected-popup="true"
              :view-key="imp.fitKey"
              :zoom-animation="false"
              :auto-fit="true"
              initial-map-size="compact"
              hint-title="지도 사용법"
              hint-text="핀을 끌어 위치를 고치거나 ‘지도에서 위치 찍기’를 누른 뒤 지도를 눌러 장소를 추가하세요."
              @pick-location="onPick"
              @move-marker="onMoveMarker"
              @select-marker="onSelectMarker"
            />
          </div>
        </div>

        <div class="travel-import-panel">
          <label
            class="travel-import-drop"
            :class="{ 'is-large': !hasFiles, 'is-dragging': dragging }"
            @dragover.prevent="dragging = true"
            @dragleave="dragging = false"
            @drop.prevent="onDrop"
          >
            <input
              type="file"
              multiple
              class="travel-import-drop__input"
              accept="image/jpeg,image/png,image/webp,image/gif,image/bmp,.jpg,.jpeg,.png,.webp,.gif,.bmp,.gpx,application/gpx+xml"
              aria-label="사진·GPX 파일 선택"
              @change="onFiles"
            />
            <strong>사진·GPX 파일을 끌어 놓거나 <span class="travel-import-drop__pick">파일 선택</span></strong>
            <small>JPG·PNG·WEBP·GIF·BMP 15MB, GPX 10MB 이하 · 사진의 날짜·위치로 제목과 일정을 자동으로 채웁니다.</small>
          </label>

          <div class="travel-import-tools">
            <button type="button" class="button button--secondary" :aria-pressed="imp.pickArmed" @click="armPick">지도에서 위치 찍기</button>
            <button type="button" class="button button--secondary" :disabled="imp.gpsLoading" @click="imp.useCurrentLocation()">현재 위치</button>
          </div>

          <form class="travel-import-search" @submit.prevent="imp.searchPlaces(query)">
            <input v-model="query" type="search" aria-label="장소 검색" placeholder="장소 이름이나 주소" maxlength="100" autocomplete="off" />
            <button type="submit" class="button button--secondary">장소 찾기</button>
          </form>
          <p v-if="imp.searchMessage" class="travel-import-hint" role="status">{{ imp.searchMessage }}</p>
          <ul v-if="imp.searchResults.length" class="travel-import-results">
            <li v-for="result in imp.searchResults" :key="result.id">
              <button type="button" class="button button--ghost" :aria-label="result.name" @click="chooseResult(result)">
                <strong>{{ result.name }}</strong>
                <small>{{ result.displayName }}</small>
              </button>
            </li>
          </ul>

          <p v-if="imp.intake.active" class="travel-import-progress" role="status">사진 정보 읽는 중 {{ imp.intake.done }}/{{ imp.intake.total }}</p>

          <div v-if="groups.length" class="travel-import-groups">
            <section v-for="group in groups" :key="group.key" class="travel-import-group">
              <h3 class="travel-import-day">{{ group.label }}</h3>
              <ol class="travel-import-list">
                <TravelImportDraftItem
                  v-for="(draft, index) in group.drafts"
                  :key="draft.id"
                  :draft="draft"
                  :problems="imp.problems.get(draft.id) || []"
                  :disabled="busy"
                  :plan-start-date="imp.planStartDate"
                  :plan-period="imp.planPeriod"
                  :focused="imp.focusedId === draft.id"
                  :index="index"
                  @update="imp.updateDraft(draft.id, $event)"
                  @locate="locate(draft)"
                  @show-on-map="showOnMap(draft)"
                  @remove-photo="imp.removePhoto(draft.id, $event)"
                  @focus="imp.focusedId = draft.id"
                />
              </ol>
            </section>
          </div>

          <section v-if="imp.rejected.length" class="travel-import-rejected" aria-label="추가하지 못한 파일">
            <div class="travel-import-rejected__head">
              <strong>추가하지 못한 파일</strong>
              <button type="button" class="button button--ghost" @click="imp.removeRejected()">지우기</button>
            </div>
            <ul>
              <li v-for="item in imp.rejected" :key="item.id"><b>{{ item.fileName }}</b> — {{ item.reason }}</li>
            </ul>
          </section>
        </div>
      </div>

      <footer class="travel-import-footer">
        <p class="travel-import-summary" role="status" aria-live="polite">{{ summaryText }}</p>
        <div class="travel-import-footer__actions">
          <button type="button" class="button button--ghost" @click="openRouteEditor">경로 직접 그리기</button>
          <button type="button" class="button button--secondary" @click="requestClose">취소</button>
          <button type="button" class="button button--primary" :disabled="!imp.canSave" @click="onSave">{{ canRetry ? '남은 항목 다시 저장' : '저장' }}</button>
        </div>
      </footer>
    </div>
  </RecordMapDialog>
</template>

<style scoped>
.travel-import-body { display: grid; gap: 14px; min-width: 0; }
.travel-import-target { display: grid; grid-template-columns: minmax(0, 1fr); gap: 10px; min-width: 0; }
.travel-import-target .field { min-width: 0; }
.travel-import-target select, .travel-import-target input[type="text"], .travel-import-target input[type="date"] { width: 100%; min-width: 0; min-height: 44px; box-sizing: border-box; }
.travel-import-target__period { display: flex; flex-wrap: wrap; gap: 10px; align-items: end; }
.travel-import-target__period .field { flex: 1 1 150px; }
.travel-import-target__check { display: inline-flex; align-items: center; gap: 8px; min-height: 44px; flex: 1 1 220px; }
.travel-import-target__check input { width: 20px; height: 20px; }
.travel-import-hint { margin: 0; font-size: .82rem; color: var(--rm-text-soft, inherit); }
.travel-import-grid { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(360px, 1fr); gap: 16px; align-items: start; min-width: 0; }
.travel-import-mapcol { position: sticky; top: 72px; display: grid; gap: 8px; min-width: 0; }
.travel-import-mapbox { height: min(560px, 62dvh); min-height: 320px; min-width: 0; }
.travel-import-mapbox .travel-import-map { height: 100%; min-width: 0; padding: 0; margin: 0; gap: 8px; }
.travel-import-dialog .travel-import-map :deep(.travel-map__canvas) { min-height: 0 !important; height: 100% !important; }
.travel-import-dialog .travel-import-map :deep(.travel-map__stage) { min-height: 0 !important; height: 100% !important; }
.travel-import-dialog .travel-import-map { overflow: hidden; grid-template-rows: auto minmax(0, 1fr) auto !important; }
.travel-import-dialog .travel-import-map :deep(.travel-map__search),
.travel-import-dialog .travel-import-map :deep(.travel-map__toolbar-group:has(> .travel-map__toolbar-label)) { display: none !important; }
.travel-import-map :deep(.travel-map__hint) { padding: 8px 12px; font-size: .78rem; }
.travel-import-map :deep(.travel-map__toolbar-group) { padding: 6px 8px; }
:global(html body .app-shell .travel-import-dialog .travel-map .travel-map__toolbar .travel-map__toolbar-button:not(.is-active)) { color: var(--text) !important; -webkit-text-fill-color: var(--text) !important; background: var(--panel-bg) !important; text-shadow: none; }
.travel-import-pickbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 0; padding: 6px 10px; border: 1px solid var(--rm-accent); color: var(--rm-text); font-weight: 600; }
.travel-import-pickbar .button { min-height: 36px; }
.travel-import-panel { display: grid; gap: 10px; min-width: 0; }
.travel-import-drop { position: relative; display: grid; gap: 4px; justify-items: center; text-align: center; padding: 14px 12px; border: 2px dashed var(--rm-line); cursor: pointer; min-width: 0; color: var(--rm-text); }
.travel-import-drop.is-large { padding: 48px 16px; }
.travel-import-drop.is-dragging { border-color: var(--rm-accent); box-shadow: inset 0 0 0 2px var(--rm-accent); }
.travel-import-drop:focus-within { outline: 2px solid var(--rm-focus); outline-offset: 2px; }
.travel-import-drop small { color: var(--rm-text-soft, inherit); }
.travel-import-drop__pick { text-decoration: underline; color: var(--rm-accent); }
.travel-import-drop__input { position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; border: 0; overflow: hidden; clip: rect(0 0 0 0); clip-path: inset(50%); white-space: nowrap; }
.travel-import-tools { display: flex; flex-wrap: wrap; gap: 8px; }
.travel-import-tools .button { flex: 1 1 140px; min-height: 44px; }
.travel-import-tools .button[aria-pressed="true"] { box-shadow: inset 0 -3px 0 var(--rm-accent) !important; border-color: var(--rm-accent) !important; }
.travel-import-search { display: flex; gap: 8px; min-width: 0; }
.travel-import-search input { flex: 1; min-width: 0; min-height: 44px; box-sizing: border-box; }
.travel-import-search .button { flex: 0 0 auto; min-height: 44px; }
.travel-import-results { display: grid; gap: 4px; margin: 0; padding: 0; list-style: none; }
.travel-import-results li { list-style: none; }
.travel-import-results .button { display: grid; gap: 2px; width: 100%; min-height: 44px; text-align: left; justify-items: start; }
.travel-import-results small { font-weight: 400; overflow-wrap: anywhere; }
.travel-import-progress { margin: 0; font-weight: 600; font-size: .85rem; }
.travel-import-groups { display: grid; gap: 14px; min-width: 0; }
.travel-import-group { display: grid; gap: 8px; min-width: 0; }
.travel-import-day { margin: 0; font-size: .85rem; padding-bottom: 4px; border-bottom: 1px solid var(--rm-line); }
.travel-import-list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; min-width: 0; }
.travel-import-rejected { display: grid; gap: 6px; padding: 10px; border: 1px solid var(--rm-wanted); font-size: .82rem; min-width: 0; }
.travel-import-rejected__head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.travel-import-rejected ul { margin: 0; padding: 0; list-style: none; display: grid; gap: 4px; }
.travel-import-rejected li { overflow-wrap: anywhere; }
.travel-import-footer { position: sticky; bottom: 0; z-index: 1; display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 12px; margin: 0 -18px -18px; padding: 12px 18px; background: var(--rm-panel); border-top: 1px solid var(--rm-line); }
.travel-import-summary { margin: 0; font-size: .85rem; font-weight: 600; min-width: 0; overflow-wrap: anywhere; }
.travel-import-footer__actions { display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; }
.travel-import-footer__actions .button { min-height: 44px; }
@media (max-width: 760px) {
  .travel-import-grid { grid-template-columns: minmax(0, 1fr); }
  .travel-import-mapcol { position: static; }
  .travel-import-mapbox { height: 38vh; min-height: 260px; }
  .travel-import-dialog .travel-import-map :deep(.travel-map__hint) { display: none !important; }
  .travel-import-map :deep(.travel-map__toolbar-group) { padding: 4px 6px; }
  .travel-import-map :deep(.travel-map__toolbar-button) { min-height: 36px; padding: 4px 10px; }
  .travel-import-drop.is-large { padding: 28px 12px; }
  .travel-import-tools .button { flex: 1 1 0; min-width: 0; padding-inline: 6px; }
}
@media (max-width: 600px) {
  .travel-import-footer { margin: 0 -12px -12px; padding: 10px 12px; }
  .travel-import-footer__actions { width: 100%; }
  .travel-import-footer__actions .button { flex: 1 1 auto; padding-inline: 8px; }
}
</style>
