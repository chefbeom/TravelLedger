<script setup>
import { computed } from 'vue'
import { buildThumbnailUrl } from '../lib/mediaPreview'
const props = defineProps({ open: Boolean, tab: String, readOnly: Boolean, busy: Boolean, loading: Boolean,
  filters: Object, snapshot: Object, places: Array, visits: Array, selected: Object,
  searchMode: String, searchText: String, locations: Array, locationLoading: Boolean, locationMessage: String })
const emit = defineEmits(['toggle', 'tab', 'search-text', 'search-mode', 'search', 'filter', 'collection', 'select', 'detail', 'photo', 'location', 'save-location', 'page', 'collections', 'share'])
const hasVisits = computed(() => !props.readOnly || props.snapshot?.visitsIncluded)
const page = computed(() => Number(props.filters?.page || 0))
function filter(key, event) { emit('filter', key, event.target.value) }
</script>
<template>
  <aside class="record-map-sidebar" :class="{ 'is-open': open }" aria-label="장소와 기록 목록" data-map-overlay @click.stop @pointerdown.stop @wheel.stop>
    <header class="record-map-sidebar__header"><strong>{{ readOnly ? '공유 장소와 기록' : '내 장소와 기록' }}</strong><div class="record-map-sidebar__actions"><slot name="actions" /><button class="button" type="button" :aria-expanded="open" :aria-label="open ? '목록 접기' : '목록 열기'" @click="emit('toggle')">{{ open ? '접기' : '목록' }}</button></div></header>
    <div class="record-map-search-modes" v-if="!readOnly"><button type="button" :aria-pressed="searchMode === 'saved'" @click="emit('search-mode', 'saved')">내 장소 검색</button><button type="button" :aria-pressed="searchMode === 'location'" @click="emit('search-mode', 'location')">새 위치 찾기</button></div>
    <form class="record-map-search" @submit.stop.prevent="emit('search')">
      <input :value="searchText" maxlength="100" type="search" :aria-label="searchMode === 'location' ? '주소·지역 검색' : '저장 장소 검색'" :placeholder="searchMode === 'location' ? '주소·지역을 입력하세요' : '장소명·지역·기록 검색'" @input="emit('search-text', $event.target.value)" />
      <button type="submit" class="button" :disabled="locationLoading || busy" aria-label="지도 검색">검색</button>
    </form>
    <div v-show="open" class="record-map-sidebar__body">
      <template v-if="searchMode === 'location'">
        <p class="record-map-sidebar__hint">입력한 주소·지역만 위치 검색 제공자에게 전달됩니다. 저장 자료와 메모는 전송하지 않습니다.</p>
        <p v-if="locationLoading || locationMessage" role="status">{{ locationLoading ? '위치를 찾는 중입니다…' : locationMessage }}</p>
        <article v-for="(location, index) in locations" :key="index" class="record-map-list-card">
          <button type="button" class="record-map-list-card__main" @click="emit('location', location)"><span class="record-map-list-card__copy"><strong>{{ location.name }}</strong><span>{{ location.displayName }}</span></span></button>
          <button class="button" type="button" @click="emit('save-location', location)">이 위치 저장</button>
        </article>
        <p class="record-map-sidebar__hint">위치 검색 © OpenStreetMap contributors · <a href="https://operations.osmfoundation.org/policies/nominatim/" target="_blank" rel="noopener noreferrer">사용 정책</a></p>
      </template>
      <template v-else>
        <nav class="record-map-sidebar__tabs" aria-label="지도 목록 종류">
          <button type="button" :aria-pressed="tab === 'places'" @click="emit('tab', 'places')">장소 목록</button><button type="button" :aria-pressed="tab === 'collections'" @click="emit('tab', 'collections')">모음</button><button v-if="hasVisits" type="button" :aria-pressed="tab === 'diary'" @click="emit('tab', 'diary')">일상 기록</button>
        </nav>
        <div class="record-map-list-filter">
          <label>방문 상태<select :value="filters.state" :disabled="busy" @change="filter('state', $event)"><option value="ALL">전체</option><option value="WANTED">가고 싶은 곳</option><option value="VISITED">방문한 곳</option></select></label>
          <details><summary>필터{{ filters.collectionId || filters.from || filters.to ? ' · 적용 중' : '' }}</summary>
            <label>장소 모음<select :value="filters.collectionId" :disabled="readOnly || busy" @change="filter('collectionId', $event)"><option value="">전체 장소</option><option v-for="c in snapshot.collections" :key="c.id" :value="String(c.id)">{{ c.name }}</option></select></label>
            <template v-if="hasVisits"><label>기록 시작일<input type="date" :value="filters.from" :disabled="busy" @change="filter('from', $event)" /></label><label>기록 종료일<input type="date" :value="filters.to" :disabled="busy" @change="filter('to', $event)" /></label></template>
            <button type="button" class="button" @click="emit('filter', 'collectionId', ''); emit('filter', 'state', 'ALL'); emit('filter', 'from', ''); emit('filter', 'to', '')">필터 초기화</button>
          </details>
        </div>
        <p v-if="loading" role="status">목록을 불러오는 중입니다…</p>
        <section v-if="tab === 'collections'" class="record-map-sidebar__collections">
          <button type="button" class="record-map-collection-card" :aria-pressed="!filters.collectionId" @click="emit('collection', '')"><span>전체 장소</span></button>
          <article v-for="c in snapshot.collections" :key="c.id" class="record-map-collection-card" :class="{ 'is-selected': String(c.id) === filters.collectionId }">
            <button type="button" class="record-map-list-card__main" @click="emit('collection', c.id)"><i :style="{ background: c.colorHex }"></i><span class="record-map-list-card__copy"><strong>{{ c.name }}</strong><span>{{ c.placeCount ?? '—' }}개 장소 · {{ c.shared ? '공유 링크 있음' : '비공개' }}</span></span></button>
          </article>
          <button v-if="!readOnly" class="button" type="button" @click="emit('collections')">모음 만들기·관리</button>
          <button v-if="!readOnly && filters.collectionId" class="button" type="button" @click="emit('share')">선택한 모음 공유</button>
        </section>
        <section v-else-if="tab === 'diary'" class="record-map-diary">
          <article v-for="visit in visits" :key="visit.id" class="record-map-list-card"><button type="button" class="record-map-list-card__main" @click="emit('detail', places.find(p => p.id === visit.placeId) || selected)"><span class="record-map-list-card__copy"><strong>{{ visit.title }}</strong><span>{{ visit.date }} {{ visit.time?.slice(0,5) }}</span><span v-if="visit.memo">{{ visit.memo }}</span></span></button><div class="record-map-sidebar__photos"><button v-for="photo in visit.photos" :key="photo.id" type="button" :aria-label="photo.name + ' 크게 보기'" @click="emit('photo', photo, visit)"><img :src="buildThumbnailUrl(photo.photoUrl, 'mini')" :alt="photo.name" loading="lazy" /></button></div></article>
          <p v-if="!visits.length" class="record-map-empty">날짜가 있는 기록이 없습니다.</p>
        </section>
        <section v-else class="record-map-sidebar__places">
          <article v-if="selected && !places.some(p => p.id === selected.id)" class="record-map-list-card is-selected"><span>지도에서 선택한 장소</span><strong>{{ selected.name }}</strong><button class="button" type="button" @click="emit('detail', selected)">방문 기록 보기</button></article>
          <article v-for="place in places" :key="place.id" class="record-map-list-card" :class="{ 'is-selected': selected?.id === place.id }" :data-place-id="place.id">
            <button type="button" class="record-map-list-card__main" :aria-label="place.name + ' 지도에서 보기'" @click="emit('select', place)"><img v-if="place.thumbnail" :src="place.thumbnail" :alt="place.name" loading="lazy" /><span v-else class="record-map-list-card__placeholder" aria-hidden="true">⌖</span><span class="record-map-list-card__copy"><strong>{{ place.name }}</strong><span>{{ [place.country, place.region].filter(Boolean).join(' · ') || '저장 장소' }}</span><span>{{ place.visited ? '방문한 곳' : '가고 싶은 곳' }}<template v-if="hasVisits"> · {{ place.visitCount || 0 }}번 기록</template></span></span></button>
            <button type="button" class="record-map-list-card__detail" @click="emit('detail', place)">방문 기록 보기</button>
          </article>
          <p v-if="!places.length && !loading" class="record-map-empty">{{ readOnly ? '공유 범위에서 일치하는 장소가 없습니다.' : '일치하는 장소가 없습니다. 검색·필터를 바꾸거나 새 장소를 저장하세요.' }}</p>
        </section>
        <footer v-if="tab !== 'collections'" class="record-map-sidebar__pagination"><span>장소 {{ snapshot.totalPlaces || 0 }}개 · {{ page + 1 }} / {{ Math.max(1, snapshot.totalPages || 0) }}</span><div><button class="button" type="button" :disabled="page <= 0 || loading" @click="emit('page', page-1)">이전</button><button class="button" type="button" :disabled="page+1 >= snapshot.totalPages || loading" @click="emit('page', page+1)">다음</button></div></footer>
      </template>
    </div>
  </aside>
</template>
<style scoped>
.record-map-sidebar { position:absolute; z-index:1001; top:12px; left:12px; width:340px; max-width:calc(100% - 104px); max-height:calc(100% - 24px); display:flex; flex-direction:column; gap:8px; padding:12px; background:var(--panel-bg); color:var(--text); border:1px solid var(--line); box-shadow:0 6px 22px #0003; box-sizing:border-box; pointer-events:auto; }
.record-map-sidebar.is-open { bottom:12px; }
.record-map-sidebar__header { display:flex; justify-content:space-between; align-items:center; gap:8px; }
.record-map-sidebar__actions { display:flex; align-items:center; gap:4px; }
.record-map-sidebar__header .button { min-height:32px; padding:5px 9px; }
.record-map-search, .record-map-search-modes, .record-map-sidebar__tabs { display:flex; gap:4px; min-width:0; }
.record-map-search input { flex:1; min-width:0; width:0; min-height:38px; padding:8px; border:1px solid var(--line); background:var(--input-bg,var(--panel-bg)); color:var(--text); }
.record-map-search .button { padding:7px 10px; min-height:38px; }
.record-map-search-modes button, .record-map-sidebar__tabs button { flex:1; padding:8px 3px; min-width:0; border:1px solid var(--line); background:transparent; color:var(--text); font:inherit; font-size:.82rem; cursor:pointer; }
button[aria-pressed="true"] { background:var(--accent-soft,#009e8c22); border-color:var(--accent,#009E8C); font-weight:700; }
.record-map-sidebar__body { flex:1; min-height:0; overflow-y:auto; overscroll-behavior:contain; display:flex; flex-direction:column; gap:10px; scrollbar-gutter:stable; }
.record-map-sidebar :is(button,input,select) { box-sizing:border-box; min-width:0 !important; max-width:100% !important; }
.record-map-sidebar__body :is(input,select) { width:100%; min-width:0; min-height:36px; box-sizing:border-box; }
.record-map-list-filter { display:flex; flex-direction:column; gap:8px; }
.record-map-list-filter label { display:grid; gap:4px; font-size:.8rem; }
.record-map-list-filter details { border:1px solid var(--line); padding:8px; }
.record-map-list-filter details label { margin:8px 0; }
.record-map-list-filter summary { cursor:pointer; font-size:.85rem; }
.record-map-sidebar__places, .record-map-sidebar__collections, .record-map-diary { display:grid; gap:8px; }
.record-map-list-card, .record-map-collection-card { min-width:0; padding:10px; border:1px solid var(--line); background:var(--panel-bg); display:grid; gap:8px; color:var(--text); }
.record-map-list-card.is-selected, .record-map-collection-card.is-selected { border-color:var(--accent,#009E8C); background:var(--accent-soft,#009e8c16); }
.record-map-list-card__main { display:flex; align-items:center; gap:10px; min-width:0; width:100%; padding:0; background:transparent; border:0; text-align:left; color:inherit; font:inherit; cursor:pointer; }
.record-map-list-card__main img, .record-map-list-card__placeholder { flex-shrink:0; width:56px; height:56px; object-fit:cover; }
.record-map-list-card__placeholder { display:grid; place-items:center; background:#009e8c18; font-size:1.8rem; }
.record-map-list-card__copy { min-width:0; display:grid; gap:4px; }
.record-map-list-card__copy strong { overflow-wrap:anywhere; line-height:1.4; }
.record-map-list-card__copy > span { font-size:.78rem; opacity:.85; overflow-wrap:anywhere; }
.record-map-list-card__detail { justify-self:end; background:transparent; border:0; color:var(--text); font:inherit; font-size:.75rem; padding:5px; cursor:pointer; text-decoration:underline; }
.record-map-collection-card i { width:12px; height:12px; flex-shrink:0; }
.record-map-sidebar__photos { display:flex; gap:5px; flex-wrap:wrap; }
.record-map-sidebar__photos button { width:64px; height:64px; padding:0; border:0; cursor:pointer; }
.record-map-sidebar__photos img { width:100%; height:100%; object-fit:cover; }
.record-map-sidebar__pagination { display:flex; flex-wrap:wrap; gap:6px; align-items:center; justify-content:space-between; padding-top:8px; border-top:1px solid var(--line); font-size:.75rem; }
.record-map-sidebar__pagination .button { padding:6px 9px; min-height:36px; }
.record-map-sidebar__pagination > div { display:flex; gap:4px; }
.record-map-sidebar__hint, .record-map-empty { font-size:.78rem; line-height:1.6; margin:0; }
.record-map-sidebar :is(button,input,select,summary):focus-visible { outline:2px solid var(--accent,#009E8C); outline-offset:2px; }
@media(max-width:760px) {
  .record-map-sidebar { top:auto; bottom:112px; left:8px; right:8px; width:auto; max-width:none; max-height:55%; padding:8px; gap:5px; }
  .record-map-sidebar.is-open { top:8px; bottom:112px; height:auto; max-height:none; }
  .record-map-sidebar:not(.is-open) .record-map-search-modes { display:none; }
  .record-map-sidebar:not(.is-open) .record-map-sidebar__header { position:absolute; right:8px; top:8px; }
  .record-map-sidebar:not(.is-open) .record-map-sidebar__header strong { display:none; }
  .record-map-sidebar:not(.is-open) .record-map-search { padding-right:45px; }
  .record-map-sidebar__header strong { font-size:.85rem; }
  .record-map-sidebar__header .button { min-height:36px !important; padding:5px 7px !important; font-size:12px !important; }
  .record-map-sidebar__tabs button { min-height:36px !important; }
  .record-map-search input { font-size:12px !important; min-height:36px !important; }
  .record-map-search .button { font-size:12px !important; min-height:36px !important; padding:5px 8px !important; }
}
</style>
