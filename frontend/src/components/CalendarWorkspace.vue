<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import SummaryCard from './SummaryCard.vue'

const CALENDAR_SCALE_KEY = 'calen-household-calendar-scale-preset'
const CALENDAR_COLLAPSE_KEY = 'calen-household-calendar-collapsed'

const calendarScalePresets = [
  { key: 'compact', label: '작게', value: 74 },
  { key: 'default', label: '기본', value: 112 },
  { key: 'expanded', label: '크게', value: 150 },
]

const props = defineProps({
  quickStats: {
    type: Array,
    default: () => [],
  },
  monthLabel: {
    type: String,
    required: true,
  },
  anchorDate: {
    type: String,
    required: true,
  },
  weekdayLabels: {
    type: Array,
    default: () => [],
  },
  calendarWeeks: {
    type: Array,
    default: () => [],
  },
  entries: {
    type: Array,
    default: () => [],
  },
  entryForm: {
    type: Object,
    required: true,
  },
  isEditingEntry: {
    type: Boolean,
    default: false,
  },
  isSubmitting: {
    type: Boolean,
    default: false,
  },
  activeSubmit: {
    type: String,
    default: '',
  },
  availableGroups: {
    type: Array,
    default: () => [],
  },
  availableDetails: {
    type: Array,
    default: () => [],
  },
  paymentMethods: {
    type: Array,
    default: () => [],
  },
  amountInput: {
    type: String,
    default: '',
  },
  amountPreview: {
    type: Number,
    default: 0,
  },
  isTimeEnabled: {
    type: Boolean,
    default: false,
  },
  quickAmountButtons: {
    type: Array,
    default: () => [],
  },
  formatAmountShortcut: {
    type: Function,
    required: true,
  },
  formatCurrency: {
    type: Function,
    required: true,
  },
  formatShortDate: {
    type: Function,
    required: true,
  },
  formatTime: {
    type: Function,
    required: true,
  },
})

const emit = defineEmits([
  'update:amountInput',
  'update:timeEnabled',
  'fill-amount',
  'add-amount',
  'submit-entry',
  'reset-entry',
  'edit-entry',
  'delete-entry',
  'change-anchor-month',
])

const selectedDate = ref(props.anchorDate)
const selectedDaySort = ref('ASC')
const calendarScalePreset = ref('default')
const isCalendarCollapsed = ref(false)
const ledgerSheetRef = ref(null)

const maxDailyExpense = computed(() => {
  const expenses = props.calendarWeeks.flat().map((day) => Number(day.summary?.expense ?? 0))
  return Math.max(...expenses, 1)
})

const selectedDateEntries = computed(() => {
  const filtered = props.entries.filter((entry) => entry.entryDate === selectedDate.value)
  return filtered.slice().sort((left, right) => {
    const leftKey = `${left.entryDate} ${left.entryTime || '99:99'} ${String(left.id).padStart(10, '0')}`
    const rightKey = `${right.entryDate} ${right.entryTime || '99:99'} ${String(right.id).padStart(10, '0')}`
    return selectedDaySort.value === 'ASC' ? leftKey.localeCompare(rightKey) : rightKey.localeCompare(leftKey)
  })
})

const calendarYear = computed(() => Number(props.anchorDate.slice(0, 4)))
const calendarMonth = computed(() => Number(props.anchorDate.slice(5, 7)))
const yearOptions = computed(() => {
  const baseYear = calendarYear.value
  return Array.from({ length: 13 }, (_, index) => baseYear - 6 + index)
})

const calendarScaleValue = computed(() => getPresetValue(calendarScalePresets, calendarScalePreset.value, 'default'))

const calendarViewStyle = computed(() => {
  const zoom = calendarScaleValue.value / 100
  return {
    '--calendar-gap': `${Math.round(10 * zoom)}px`,
    '--calendar-week-gap': `${Math.round(8 * zoom)}px`,
    '--calendar-day-min-height': `${Math.round(146 * zoom)}px`,
    '--calendar-day-padding': `${Math.round(12 * zoom)}px`,
    '--calendar-expense-total-size': `${Math.max(1, 1.18 * zoom).toFixed(2)}rem`,
    '--calendar-metric-size': `${Math.max(0.72, 0.8 * zoom).toFixed(2)}rem`,
    '--calendar-toolbar-gap': `${Math.round(18 * zoom)}px`,
  }
})

const normalizedSelectedDateEntries = computed(() =>
  selectedDateEntries.value.map((entry) => ({
    ...entry,
    visibleMemo: stripImportedMemo(entry.memo),
  })),
)

const hasSelectedMemoColumn = computed(() => normalizedSelectedDateEntries.value.some((entry) => entry.visibleMemo))
const selectedDateCountLabel = computed(() => `${normalizedSelectedDateEntries.value.length}건`)

watch(
  () => props.anchorDate,
  (value) => {
    const currentMonthKey = value.slice(0, 7)
    if (!selectedDate.value || !selectedDate.value.startsWith(currentMonthKey)) {
      selectedDate.value = value
    }
  },
  { immediate: true },
)

watch(selectedDate, (value) => {
  if (!props.isEditingEntry) {
    props.entryForm.entryDate = value
  }
})

watch(calendarScalePreset, (value) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(CALENDAR_SCALE_KEY, value)
  }
})

onMounted(() => {
  if (typeof window === 'undefined') {
    return
  }

  const savedScale = window.localStorage.getItem(CALENDAR_SCALE_KEY)
  const savedCollapsed = window.localStorage.getItem(CALENDAR_COLLAPSE_KEY)

  if (savedScale) {
    calendarScalePreset.value = normalizePresetKey(calendarScalePresets, savedScale, 'default')
  }

  if (savedCollapsed) {
    isCalendarCollapsed.value = savedCollapsed === 'true'
  }
})

watch(isCalendarCollapsed, (value) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(CALENDAR_COLLAPSE_KEY, value ? 'true' : 'false')
  }
})

function getPresetValue(presets, key, fallbackKey) {
  return presets.find((item) => item.key === key)?.value ?? presets.find((item) => item.key === fallbackKey)?.value ?? presets[0].value
}

function normalizePresetKey(presets, value, fallbackKey) {
  const direct = presets.find((item) => item.key === value)
  if (direct) {
    return direct.key
  }

  const numeric = Number(value)
  if (Number.isFinite(numeric)) {
    const nearest = presets.slice().sort((left, right) => Math.abs(left.value - numeric) - Math.abs(right.value - numeric))[0]
    return nearest?.key || fallbackKey
  }

  return fallbackKey
}

function stripImportedMemo(value) {
  if (!value) {
    return ''
  }

  return String(value)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('Imported from Excel'))
    .join('\n')
}

function getExpenseRatio(day) {
  const amount = Number(day.summary?.expense ?? 0)
  if (!amount) {
    return 0
  }

  return Math.max(12, Math.round((amount / maxDailyExpense.value) * 100))
}

function formatCompactCurrency(value) {
  const amount = Number(value ?? 0)
  if (!amount) {
    return '0원'
  }

  if (amount >= 10000) {
    return `${(amount / 10000).toFixed(amount >= 100000 ? 0 : 1).replace(/\.0$/, '')}만`
  }

  return `${amount.toLocaleString('ko-KR')}원`
}

function updateAnchorMonth(year, month) {
  const safeMonth = `${Math.min(12, Math.max(1, Number(month) || 1))}`.padStart(2, '0')
  emit('change-anchor-month', `${Number(year)}-${safeMonth}-01`)
}

function handleChangeYear(event) {
  updateAnchorMonth(event.target.value, calendarMonth.value)
}

function handleChangeMonth(event) {
  updateAnchorMonth(calendarYear.value, event.target.value)
}

function shiftAnchorYear(offset) {
  updateAnchorMonth(calendarYear.value + Number(offset || 0), calendarMonth.value)
}

function shiftAnchorMonth(offset) {
  const nextDate = new Date(calendarYear.value, calendarMonth.value - 1 + Number(offset || 0), 1)
  updateAnchorMonth(nextDate.getFullYear(), nextDate.getMonth() + 1)
}

async function handleSelectDay(day) {
  selectedDate.value = day.date

  if (!props.isEditingEntry) {
    props.entryForm.entryDate = day.date
  }

  if (!day.inCurrentMonth) {
    emit('change-anchor-month', day.date)
  }

  await nextTick()
  ledgerSheetRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function getMonthTag(day) {
  if (day.showMonthTag) {
    return `${day.monthNumber}월`
  }
  return ''
}

function toggleCalendarCollapsed() {
  isCalendarCollapsed.value = !isCalendarCollapsed.value
}
</script>

<template>
  <div class="workspace-stack">
    <section class="summary-grid summary-grid--compact">
      <SummaryCard v-for="card in quickStats" :key="card.key" :card="card" />
    </section>

    <section class="panel household-calendar-panel household-calendar-layout" :style="calendarViewStyle">
      <div class="panel__header">
        <div>
          <h2>{{ monthLabel }}</h2>
          <p>달력을 넓게 보고 날짜를 누르면 바로 아래 거래 시트로 이동해 해당 날짜 거래를 바로 확인할 수 있습니다.</p>
        </div>
        <div class="household-calendar-header-actions">
          <span class="panel__badge">{{ anchorDate.slice(0, 7) }}</span>
          <button class="button button--secondary" type="button" @click="toggleCalendarCollapsed">
            {{ isCalendarCollapsed ? '달력 펼치기' : '달력 접기' }}
          </button>
        </div>
      </div>

      <div v-if="!isCalendarCollapsed" class="calendar-toolbar">
        <div class="calendar-stepper">
          <span class="calendar-stepper__label">연도</span>
          <div class="calendar-stepper__controls">
            <button type="button" class="calendar-stepper__arrow" aria-label="이전 연도" @click="shiftAnchorYear(-1)">&lt;</button>
            <label class="field calendar-stepper__field">
              <select :value="calendarYear" @change="handleChangeYear">
                <option v-for="year in yearOptions" :key="year" :value="year">{{ year }}년</option>
              </select>
            </label>
            <button type="button" class="calendar-stepper__arrow" aria-label="다음 연도" @click="shiftAnchorYear(1)">&gt;</button>
          </div>
        </div>
        <div class="calendar-stepper">
          <span class="calendar-stepper__label">월</span>
          <div class="calendar-stepper__controls">
            <button type="button" class="calendar-stepper__arrow" aria-label="이전 달" @click="shiftAnchorMonth(-1)">&lt;</button>
            <label class="field calendar-stepper__field">
              <select :value="calendarMonth" @change="handleChangeMonth">
                <option v-for="month in 12" :key="month" :value="month">{{ month }}월</option>
              </select>
            </label>
            <button type="button" class="calendar-stepper__arrow" aria-label="다음 달" @click="shiftAnchorMonth(1)">&gt;</button>
          </div>
        </div>
      </div>

      <div v-if="!isCalendarCollapsed" class="calendar-size-toolbar">
        <div class="calendar-size-toolbar__block">
          <span class="calendar-size-toolbar__label">달력 크기</span>
          <div class="calendar-size-toggle">
            <button
              v-for="preset in calendarScalePresets"
              :key="preset.key"
              type="button"
              class="calendar-size-toggle__button"
              :class="{ 'is-active': calendarScalePreset === preset.key }"
              @click="calendarScalePreset = preset.key"
            >
              {{ preset.label }}
            </button>
          </div>
        </div>
        <strong class="calendar-size-toolbar__hint">현재 {{ calendarScalePresets.find((item) => item.key === calendarScalePreset)?.label }}</strong>
      </div>

      <div v-if="!isCalendarCollapsed" class="calendar-shell">
        <div class="calendar">
          <div class="calendar__weekdays">
            <span v-for="weekday in weekdayLabels" :key="weekday">{{ weekday }}</span>
          </div>

          <div class="calendar__weeks">
            <div v-for="(week, weekIndex) in calendarWeeks" :key="`week-${weekIndex}`" class="calendar__week">
              <article
                v-for="day in week"
                :key="day.date"
                :class="[
                  'calendar__day',
                  {
                    'calendar__day--muted': !day.inCurrentMonth,
                    'calendar__day--active': Number(day.summary.expense) > 0,
                    'calendar__day--selected': selectedDate === day.date,
                  },
                ]"
                role="button"
                tabindex="0"
                @click="handleSelectDay(day)"
                @keydown.enter.prevent="handleSelectDay(day)"
              >
                <div class="calendar__day-head">
                  <div class="calendar__day-stamp">
                    <span v-if="getMonthTag(day)" class="calendar__month-tag">{{ getMonthTag(day) }}</span>
                    <strong>{{ day.dayNumber }}</strong>
                  </div>
                  <span>{{ day.summary.entryCount }}건</span>
                </div>
                <div class="calendar__expense-block">
                  <span class="calendar__label">오늘 사용</span>
                  <strong class="calendar__expense-total">
                    {{ formatCompactCurrency(day.summary.expense) }}
                  </strong>
                </div>
                <div class="calendar__metrics">
                  <div class="calendar__metric calendar__metric--expense">
                    <span>지출</span>
                    <strong class="is-expense">{{ formatCurrency(day.summary.expense) }}</strong>
                  </div>
                  <div class="calendar__metric calendar__metric--income">
                    <span>수입</span>
                    <strong class="is-income">{{ formatCurrency(day.summary.income) }}</strong>
                  </div>
                </div>
                <div class="calendar__bar">
                  <span :style="{ width: `${getExpenseRatio(day)}%` }" />
                </div>
              </article>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="household-calendar-collapsed-note">
        <strong>{{ formatShortDate(selectedDate) }}</strong>
        <span>달력을 접어두었습니다. 다시 펼치면 날짜별 흐름을 바로 확인할 수 있습니다.</span>
      </div>
    </section>

    <div class="household-ledger-grid">
      <section class="panel household-entry-panel">
        <div class="panel__header">
          <div>
            <h2>{{ isEditingEntry ? '거래 수정' : '빠른 거래 입력' }}</h2>
            <p>선택한 날짜를 기본값으로 이어받아 빠르게 기록하고, 시간 입력이 필요할 때만 켜서 저장할 수 있습니다.</p>
          </div>
          <span class="panel__badge">{{ formatShortDate(entryForm.entryDate) }}</span>
        </div>

        <div class="entry-editor">
          <div class="entry-editor__amount">
            <div class="entry-type-toggle">
              <button
                :class="['toggle-chip', { 'toggle-chip--active': entryForm.entryType === 'EXPENSE' }]"
                @click="entryForm.entryType = 'EXPENSE'"
              >
                지출
              </button>
              <button
                :class="['toggle-chip', { 'toggle-chip--active': entryForm.entryType === 'INCOME' }]"
                @click="entryForm.entryType = 'INCOME'"
              >
                수입
              </button>
            </div>

            <label class="field field--amount">
              <span class="field__label">금액</span>
              <div class="amount-input">
                <span>₩</span>
                <input
                  :value="amountInput"
                  type="text"
                  inputmode="numeric"
                  placeholder="예: 48,000"
                  @input="emit('update:amountInput', $event.target.value)"
                />
              </div>
              <small class="field__hint">현재 입력 금액 {{ formatCurrency(amountPreview) }}</small>
            </label>

            <div class="amount-shortcuts">
              <button
                v-for="value in quickAmountButtons"
                :key="value"
                class="button button--secondary amount-shortcuts__button"
                @click="emit('fill-amount', value)"
              >
                {{ formatAmountShortcut(value) }}
              </button>
              <button class="button button--secondary amount-shortcuts__button" @click="emit('add-amount', 5000)">
                +5천
              </button>
              <button class="button button--secondary amount-shortcuts__button" @click="emit('add-amount', 10000)">
                +1만
              </button>
            </div>
          </div>

          <div class="entry-editor__fields">
            <label class="field">
              <span class="field__label">날짜</span>
              <input v-model="entryForm.entryDate" type="date" />
            </label>

            <label class="field household-time-field">
              <span class="field__label">시간</span>
              <label class="checkbox-row household-time-toggle">
                <input
                  :checked="isTimeEnabled"
                  type="checkbox"
                  @change="emit('update:timeEnabled', $event.target.checked)"
                />
                <span>시간 입력 사용</span>
              </label>
              <input v-model="entryForm.entryTime" type="time" :disabled="!isTimeEnabled" />
              <small class="field__hint">시간 입력을 끄면 자동으로 00:00으로 저장됩니다.</small>
            </label>

            <label class="field field--full">
              <span class="field__label">제목</span>
              <input v-model="entryForm.title" type="text" placeholder="예: 식사, 택시, 급여" />
            </label>

            <label class="field">
              <span class="field__label">결제수단</span>
              <select v-model="entryForm.paymentMethodId">
                <option v-for="payment in paymentMethods" :key="payment.id" :value="String(payment.id)">
                  {{ payment.name }}
                </option>
              </select>
            </label>

            <label class="field">
              <span class="field__label">대분류</span>
              <select v-model="entryForm.categoryGroupId">
                <option v-for="group in availableGroups" :key="group.id" :value="String(group.id)">
                  {{ group.name }}
                </option>
              </select>
            </label>

            <label class="field">
              <span class="field__label">소분류</span>
              <select v-model="entryForm.categoryDetailId">
                <option value="">소분류 없음</option>
                <option v-for="detail in availableDetails" :key="detail.id" :value="String(detail.id)">
                  {{ detail.name }}
                </option>
              </select>
            </label>

            <label class="field field--full">
              <span class="field__label">메모</span>
              <input v-model="entryForm.memo" type="text" placeholder="상세 메모를 남기고 싶다면 입력해 주세요." />
            </label>
          </div>
        </div>

        <div class="entry-editor__actions">
          <button class="button button--primary" :disabled="isSubmitting" @click="emit('submit-entry')">
            {{
              isSubmitting && activeSubmit === 'entry'
                ? '저장 중...'
                : isEditingEntry
                  ? '거래 수정'
                  : '거래 등록'
            }}
          </button>
          <button v-if="isEditingEntry" class="button button--secondary" @click="emit('reset-entry')">
            편집 취소
          </button>
        </div>
      </section>

      <section ref="ledgerSheetRef" class="panel household-sheet-panel">
        <div class="panel__header">
          <div>
            <h2>{{ formatShortDate(selectedDate) }} 거래 시트</h2>
            <p>달력에서 고른 날짜의 거래만 모아서 바로 수정하거나 삭제할 수 있습니다.</p>
          </div>
          <div class="household-sheet-header">
            <span class="panel__badge">{{ selectedDateCountLabel }}</span>
            <div class="scope-toggle">
              <button class="button" :class="{ 'button--primary': selectedDaySort === 'ASC' }" @click="selectedDaySort = 'ASC'">시간 오름차순</button>
              <button class="button" :class="{ 'button--primary': selectedDaySort === 'DESC' }" @click="selectedDaySort = 'DESC'">시간 내림차순</button>
            </div>
          </div>
        </div>

        <div class="sheet-table-wrap household-sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>시간</th>
                <th>구분</th>
                <th>제목</th>
                <th>카테고리</th>
                <th>결제수단</th>
                <th>금액</th>
                <th v-if="hasSelectedMemoColumn">메모</th>
                <th>작업</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in normalizedSelectedDateEntries" :key="entry.id">
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
                <td>{{ entry.paymentMethodName }}</td>
                <td :class="['sheet-table__amount', entry.entryType === 'INCOME' ? 'is-income' : 'is-expense']">
                  {{ formatCurrency(entry.amount) }}
                </td>
                <td v-if="hasSelectedMemoColumn" class="sheet-table__memo">{{ entry.visibleMemo || '-' }}</td>
                <td class="sheet-table__actions">
                  <button class="button button--ghost" @click="emit('edit-entry', entry)">수정</button>
                  <button class="button button--danger" @click="emit('delete-entry', entry)">삭제</button>
                </td>
              </tr>
              <tr v-if="!normalizedSelectedDateEntries.length">
                <td :colspan="hasSelectedMemoColumn ? 8 : 7" class="sheet-table__empty">선택한 날짜에는 거래가 없습니다.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </div>
</template>
