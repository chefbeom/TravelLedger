<script setup>
import { ref } from 'vue'
import BaseDialog from './BaseDialog.vue'
import CalendarIcon from './CalendarIcon.vue'

const props = defineProps({
  filters: { type: Object, required: true },
  layers: { type: Object, required: true },
  categories: { type: Array, default: () => [] },
  paymentMethods: { type: Array, default: () => [] },
  collections: { type: Array, default: () => [] },
  plans: { type: Array, default: () => [] },
  sharedOptions: { type: Array, default: () => [] },
  sharedLoading: { type: Boolean, default: false },
  sharedBusy: { type: Boolean, default: false },
  sharedActionError: { type: String, default: '' },
  mapOptions: { type: Array, default: () => [] },
  mapLoading: { type: Boolean, default: false },
  mapBusy: { type: Boolean, default: false },
  mapActionError: { type: String, default: '' },
  undated: { type: Object, default: null },
  undatedError: { type: String, default: '' },
  undatedBusy: { type: Boolean, default: false },
  toggleShared: { type: Function, required: true },
  toggleMap: { type: Function, required: true },
  addMap: { type: Function, required: true },
})
const emit = defineEmits(['close', 'reset', 'load-undated', 'undated-page', 'open-place', 'open-travel'])
const mapLink = ref('')
const anyLayer = () => Object.values(props.layers).some(Boolean)
const showCollections = () => props.layers.places || props.layers.travel
const showPlans = () => props.layers.places || props.layers.travel || props.layers.routes

async function onShared(item, event) {
  if (!await props.toggleShared(item, event.target.checked)) event.target.checked = item.selected
}
async function onMap(item, event) {
  if (!await props.toggleMap(item, event.target.checked)) event.target.checked = item.selected
}
async function submitMap() {
  if (await props.addMap(mapLink.value)) mapLink.value = ''
}
function onUndatedToggle(event) {
  if (event.target.open && !props.undated) emit('load-undated')
}
</script>

<template>
  <BaseDialog variant="drawer" class="integrated-calendar__drawer" labelledby="integrated-calendar-drawer-title" @close="emit('close')">
    <div class="integrated-calendar__drawer-body">
      <header class="integrated-calendar__drawer-head">
        <h2 id="integrated-calendar-drawer-title">필터와 공유 설정</h2>
        <button type="button" class="integrated-calendar__drawer-x" aria-label="닫기" @click="emit('close')"><CalendarIcon name="close" :size="18" /></button>
      </header>
      <div class="integrated-calendar__drawer-scroll">
        <div role="group" aria-label="달력 필터" class="integrated-calendar__filters">
          <template v-if="layers.ledger">
            <label>거래 구분 <select v-model="filters.entryType"><option value="">전체</option><option value="INCOME">수입</option><option value="EXPENSE">지출</option></select></label>
            <label>분류 <select v-model="filters.categoryId"><option value="">전체</option><option v-for="item in categories" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
            <label>결제수단 <select v-model="filters.paymentMethodId"><option value="">전체</option><option v-for="item in paymentMethods" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
          </template>
          <label v-if="showCollections()">장소 분류 <select v-model="filters.collectionId"><option value="">전체</option><option v-for="item in collections" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
          <label v-if="showPlans()">여행 <select v-model="filters.planId"><option value="">전체</option><option v-for="item in plans" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
          <p v-if="!anyLayer()">켜진 자료가 없어 세부 필터가 없습니다.</p>
          <p v-if="filters.planId && showPlans()"><small>여행 필터에서는 실제로 해당 여행과 연결된 장소만 표시합니다. 별도 방문은 여행과의 관계를 추측하지 않습니다.</small></p>
        </div>

        <section class="integrated-calendar__drawer-section">
          <h3>공유받은 여행 선택</h3>
          <p>선택한 여행만 달력에 표시합니다. 선택 해제해도 원본 공유는 삭제되지 않으며, 본인 가계부 합계에 더하지 않습니다.</p>
          <p v-if="sharedActionError" role="alert" class="integrated-calendar__drawer-error">{{ sharedActionError }}</p>
          <p v-if="sharedLoading">공유 자료를 확인하는 중입니다.</p>
          <p v-else-if="!sharedOptions.length">선택할 수 있는 공유 여행이 없습니다.</p>
          <div v-for="item in sharedOptions" :key="`${item.type}-${item.id}`"><label><input type="checkbox" :checked="item.selected" :disabled="sharedBusy || sharedLoading" @change="onShared(item, $event)"> {{ item.title }} · {{ item.owner }} ({{ item.type === 'exhibit' ? '공유 전시' : '공유 기록' }})</label></div>
        </section>

        <section class="integrated-calendar__drawer-section">
          <h3>공유받은 기록 지도 선택</h3>
          <p>받은 기록 지도 공유 주소를 추가하면 이 계정에서만 선택할 수 있습니다. 주소 자체는 저장하지 않습니다. 방문 기록이 포함된 공유만 날짜별로 표시됩니다.</p>
          <form class="integrated-calendar__drawer-form" @submit.prevent="submitMap"><label>기록 지도 공유 주소 <input v-model="mapLink" type="text" autocomplete="off" spellcheck="false"></label> <button type="submit" :disabled="mapBusy">공유 지도 추가</button></form>
          <p v-if="mapActionError" role="alert" class="integrated-calendar__drawer-error">{{ mapActionError }}</p>
          <p v-if="mapLoading">공유 지도를 확인하는 중입니다.</p>
          <p v-else-if="!mapOptions.length">추가된 공유 지도가 없습니다.</p>
          <div v-for="item in mapOptions" :key="item.id"><label><input type="checkbox" :checked="item.selected" :disabled="mapBusy || mapLoading" @change="onMap(item, $event)"> {{ item.title }} · {{ item.includesVisits ? '방문 기록 포함' : '장소만 공유' }}</label></div>
        </section>

        <details class="integrated-calendar__drawer-section integrated-calendar__undated" @toggle="onUndatedToggle">
          <summary>날짜 미지정 자료</summary>
          <p>방문 날짜가 없는 장소와 기간이 없는 여행입니다. 기록일을 임의로 지정하지 않습니다.</p>
          <p v-if="undatedError" class="integrated-calendar__drawer-error" role="alert">{{ undatedError }} <button type="button" @click="emit('load-undated')">재시도</button></p>
          <p v-else-if="undatedBusy && !undated">불러오는 중</p>
          <template v-if="undated">
            <section v-for="[type, label] in [['places', '날짜 없는 장소'], ['trips', '기간 없는 여행']]" :key="type">
              <strong>{{ label }} · {{ undated[type].total }}개</strong>
              <ul><li v-for="item in undated[type].items" :key="item.id">{{ item.title }} <button type="button" @click="type === 'places' ? emit('open-place', { placeId: item.id }) : emit('open-travel', { sourceType: 'plan', planId: item.id })">원본 보기</button></li></ul>
              <div><button type="button" :disabled="undated[type].page === 0 || undatedBusy" @click="emit('undated-page', type, -1)">이전</button> <span>{{ undated[type].page + 1 }}쪽</span> <button type="button" :disabled="(undated[type].page + 1) * undated[type].size >= undated[type].total || undatedBusy" @click="emit('undated-page', type, 1)">다음</button></div>
            </section>
          </template>
        </details>
      </div>
      <footer class="integrated-calendar__drawer-foot">
        <button type="button" class="button button--ghost" @click="emit('reset')">필터 초기화</button>
        <button type="button" class="button button--primary" @click="emit('close')">닫기</button>
      </footer>
    </div>
  </BaseDialog>
</template>

<style scoped>
.integrated-calendar__drawer-body { box-sizing: border-box; width: 100%; display: flex; flex-direction: column; height: 100%; min-height: 0; }
.integrated-calendar__drawer-head { display: flex; align-items: center; justify-content: space-between; gap: .5rem; padding: .75rem 1rem; border-bottom: 1px solid var(--ic-line); }
.integrated-calendar__drawer-head h2 { margin: 0; font-size: 1.125rem; }
.integrated-calendar__drawer-x { display: inline-flex; align-items: center; justify-content: center; min-width: 2.75rem; min-height: 2.75rem; padding: 0; }
.integrated-calendar__drawer-scroll { flex: 1 1 auto; overflow-y: auto; overflow-x: hidden; display: grid; grid-template-columns: minmax(0, 1fr); align-content: start; gap: 1rem; padding: 1rem; min-height: 0; }
.integrated-calendar__drawer-scroll p { margin: 0; }
.integrated-calendar__filters { display: grid; grid-template-columns: minmax(0, 1fr); gap: .75rem; }
.integrated-calendar__filters label { display: grid; gap: .25rem; }
.integrated-calendar__drawer-scroll select, .integrated-calendar__drawer-scroll input[type='text'] { min-height: 2.75rem; box-sizing: border-box; width: 100%; max-width: 100%; }
.integrated-calendar__drawer-section { display: grid; gap: .5rem; padding-top: .75rem; border-top: 1px solid var(--ic-line); }
.integrated-calendar__drawer-section h3 { margin: 0; font-size: 1rem; }
.integrated-calendar__drawer-section label:has(input[type='checkbox']) { display: inline-flex; align-items: center; gap: .5rem; min-height: 2.75rem; }
.integrated-calendar__drawer-section input[type='checkbox'] { flex: none; width: 1.1rem; height: 1.1rem; }
.integrated-calendar__drawer-form { display: grid; gap: .5rem; }
.integrated-calendar__drawer-form label { display: grid; gap: .25rem; }
.integrated-calendar__drawer-section button, .integrated-calendar__drawer-form button { min-height: 2.75rem; }
.integrated-calendar__undated summary { cursor: pointer; min-height: 2.75rem; display: flex; align-items: center; font-weight: 700; }
.integrated-calendar__undated section { display: grid; gap: .25rem; margin-top: .5rem; border-top: 0; padding-top: 0; }
.integrated-calendar__undated ul { margin: 0; padding-left: 1.25rem; }
.integrated-calendar__undated li { min-height: 2.75rem; }
.integrated-calendar__drawer-error { padding: .5rem; border: 1px solid var(--ic-warning); background: var(--ic-warning-soft); }
.integrated-calendar__drawer-foot { display: flex; justify-content: space-between; gap: .5rem; padding: .75rem 1rem; border-top: 1px solid var(--ic-line); background: var(--ic-panel); }
.integrated-calendar__drawer-foot .button { min-height: 2.75rem; }
</style>
