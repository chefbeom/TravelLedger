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
])

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
            <td class="sheet-table__title">{{ entry.title }}</td>
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
              <div class="sheet-table__actions-inner">
                <button type="button" class="button button--ghost" @click="emit('edit-entry', entry)">수정</button>
                <button type="button" class="button button--ghost" @click="emit('share-entry', entry)">공유</button>
                <button type="button" class="button button--danger" @click="emit('delete-entry', entry)">삭제</button>
              </div>
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
</style>
