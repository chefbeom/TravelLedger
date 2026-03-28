<script setup>
import { computed } from 'vue'
import BarChartCard from './BarChartCard.vue'
import BreakdownList from './BreakdownList.vue'
import ComparisonTable from './ComparisonTable.vue'
import DonutChartCard from './DonutChartCard.vue'
import SummaryCard from './SummaryCard.vue'

const chartPalette = ['#3182f6', '#12b886', '#f59f00', '#ff6b6b', '#7c5cff', '#00b8d9', '#fd7e14', '#5c7cfa']

const props = defineProps({
  route: {
    type: String,
    required: true,
  },
  statsControls: {
    type: Object,
    required: true,
  },
  searchForm: {
    type: Object,
    required: true,
  },
  presetOptions: {
    type: Array,
    default: () => [],
  },
  statsCards: {
    type: Array,
    default: () => [],
  },
  statsRangeLabel: {
    type: String,
    required: true,
  },
  comparisonRows: {
    type: Array,
    default: () => [],
  },
  comparisonBadge: {
    type: String,
    required: true,
  },
  searchResults: {
    type: Array,
    default: () => [],
  },
  searchSummary: {
    type: Object,
    required: true,
  },
  insights: {
    type: Object,
    required: true,
  },
  pastComparisons: {
    type: Array,
    default: () => [],
  },
  expenseBreakdown: {
    type: Array,
    default: () => [],
  },
  paymentBreakdown: {
    type: Array,
    default: () => [],
  },
  paymentMethods: {
    type: Array,
    default: () => [],
  },
  categories: {
    type: Array,
    default: () => [],
  },
  formatCurrency: {
    type: Function,
    required: true,
  },
  formatShortDate: {
    type: Function,
    required: true,
  },
  formatFullDate: {
    type: Function,
    required: true,
  },
  formatDateRange: {
    type: Function,
    required: true,
  },
  formatTime: {
    type: Function,
    required: true,
  },
})

const comparisonChartItems = computed(() =>
  props.comparisonRows.slice(-8).map((row, index) => ({
    label: row.label,
    value: Number(row.expense ?? 0),
    caption: `수입 ${props.formatCurrency(row.income)}`,
    color: chartPalette[index % chartPalette.length],
  })),
)

const expenseDonutItems = computed(() =>
  props.expenseBreakdown.slice(0, 6).map((item, index) => ({
    label: item.detailName ? `${item.groupName} / ${item.detailName}` : item.groupName,
    value: Number(item.totalAmount ?? 0),
    caption: `${item.entryCount}건`,
    color: chartPalette[index % chartPalette.length],
  })),
)

const paymentDonutItems = computed(() =>
  props.paymentBreakdown.slice(0, 6).map((item, index) => ({
    label: `${item.paymentMethodName} (${item.kind})`,
    value: Number(item.totalAmount ?? 0),
    caption: `${item.entryCount}건`,
    color: chartPalette[index % chartPalette.length],
  })),
)

const hourlyChartItems = computed(() =>
  (props.insights.hourlySeries ?? []).map((item, index) => ({
    label: item.label,
    value: Number(item.value ?? 0),
    color: chartPalette[index % chartPalette.length],
  })),
)

const weekdayChartItems = computed(() =>
  (props.insights.weekdaySeries ?? []).map((item, index) => ({
    label: item.label.replace('요일', ''),
    value: Number(item.value ?? 0),
    color: chartPalette[index % chartPalette.length],
  })),
)

const weekOfMonthChartItems = computed(() =>
  (props.insights.weekOfMonthSeries ?? []).map((item, index) => ({
    label: item.label,
    value: Number(item.value ?? 0),
    color: chartPalette[index % chartPalette.length],
  })),
)

const monthOfYearChartItems = computed(() =>
  (props.insights.monthOfYearSeries ?? []).map((item, index) => ({
    label: item.label,
    value: Number(item.value ?? 0),
    color: chartPalette[index % chartPalette.length],
  })),
)
</script>

<template>
  <div class="workspace-stack">
    <section class="panel toss-control-panel">
      <div class="panel__header">
        <div>
          <h2>통계 루트</h2>
          <p>일, 주, 월, 분기, 년, 사용자 지정 기간을 한 화면에서 비교합니다.</p>
        </div>
        <span class="panel__badge">{{ statsRangeLabel }}</span>
      </div>

      <div class="stats-toolbar">
        <div class="preset-chips">
          <button
            v-for="option in presetOptions"
            :key="option.value"
            :class="['preset-chip', { 'preset-chip--active': statsControls.preset === option.value }]"
            @click="statsControls.preset = option.value"
          >
            {{ option.label }}
          </button>
        </div>

        <div class="stats-toolbar__fields">
          <label v-if="statsControls.preset === 'CUSTOM'" class="field">
            <span class="field__label">조회 시작일</span>
            <input v-model="statsControls.customFrom" type="date" />
          </label>

          <label v-if="statsControls.preset === 'CUSTOM'" class="field">
            <span class="field__label">조회 종료일</span>
            <input v-model="statsControls.customTo" type="date" />
          </label>

          <label class="field">
            <span class="field__label">비교 단위</span>
            <select v-model="statsControls.compareUnit">
              <option value="DAY">일</option>
              <option value="WEEK">주</option>
              <option value="MONTH">월</option>
              <option value="QUARTER">분기</option>
              <option value="YEAR">년</option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">비교 구간 수</span>
            <select v-model.number="statsControls.comparePeriods">
              <option :value="6">6</option>
              <option :value="8">8</option>
              <option :value="12">12</option>
              <option :value="16">16</option>
            </select>
          </label>
        </div>
      </div>
    </section>

    <template v-if="route === 'stats-overview'">
      <section class="summary-grid">
        <SummaryCard v-for="card in statsCards" :key="card.key" :card="card" />
      </section>

      <section class="chart-grid chart-grid--overview">
        <BarChartCard
          title="구간별 지출 추이"
          subtitle="최근 비교 구간 기준으로 지출 흐름을 막대 그래프로 보여줍니다."
          :items="comparisonChartItems"
          :format-value="formatCurrency"
          empty-text="비교할 지출 데이터가 없습니다."
        />
        <DonutChartCard
          title="지출 카테고리 비중"
          subtitle="가장 많이 쓴 카테고리를 색상으로 구분합니다."
          :items="expenseDonutItems"
          :format-value="formatCurrency"
          empty-text="카테고리 지출 데이터가 없습니다."
        />
        <DonutChartCard
          title="결제수단 비중"
          subtitle="카드, 현금, 포인트 사용 비중을 확인합니다."
          :items="paymentDonutItems"
          :format-value="formatCurrency"
          empty-text="결제수단 데이터가 없습니다."
        />
      </section>

      <div class="content-grid content-grid--stats">
        <section class="panel panel--wide">
          <div class="panel__header">
            <div>
              <h2>기간 비교 표</h2>
              <p>선택한 단위 기준으로 수입, 지출, 잔액 흐름을 표로 비교합니다.</p>
            </div>
            <span class="panel__badge">{{ comparisonBadge }}</span>
          </div>
          <ComparisonTable :rows="comparisonRows" />
        </section>

        <section class="panel">
          <BreakdownList title="지출 카테고리 상세" :items="expenseBreakdown" />
        </section>

        <section class="panel">
          <BreakdownList title="결제수단 상세" :items="paymentBreakdown" />
        </section>
      </div>
    </template>

    <template v-else-if="route === 'stats-search'">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>검색</h2>
            <p>제목, 금액, 결제방법, 대분류 조건을 조합해서 거래를 찾습니다.</p>
          </div>
          <span class="panel__badge">{{ searchResults.length }}건</span>
        </div>

        <div class="search-grid">
          <label class="field field--full">
            <span class="field__label">키워드</span>
            <input v-model="searchForm.keyword" type="text" placeholder="제목, 메모, 카테고리, 결제수단 검색" />
          </label>

          <label class="field">
            <span class="field__label">구분</span>
            <select v-model="searchForm.entryType">
              <option value="">전체</option>
              <option value="EXPENSE">지출</option>
              <option value="INCOME">수입</option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">결제수단</span>
            <select v-model="searchForm.paymentMethodId">
              <option value="">전체</option>
              <option v-for="payment in paymentMethods" :key="payment.id" :value="String(payment.id)">
                {{ payment.name }}
              </option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">대분류</span>
            <select v-model="searchForm.categoryGroupId">
              <option value="">전체</option>
              <option v-for="group in categories" :key="group.id" :value="String(group.id)">
                {{ group.entryType === 'INCOME' ? '수입' : '지출' }} / {{ group.name }}
              </option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">최소 금액</span>
            <input v-model="searchForm.minAmount" type="number" min="0" placeholder="0" />
          </label>

          <label class="field">
            <span class="field__label">최대 금액</span>
            <input v-model="searchForm.maxAmount" type="number" min="0" placeholder="제한 없음" />
          </label>

          <label class="field">
            <span class="field__label">정렬</span>
            <select v-model="searchForm.sortBy">
              <option value="DATE_DESC">최신순</option>
              <option value="DATE_ASC">오래된순</option>
              <option value="AMOUNT_DESC">금액 큰순</option>
              <option value="AMOUNT_ASC">금액 작은순</option>
            </select>
          </label>
        </div>

        <div class="search-summary">
          <div>
            <strong>{{ searchSummary.count }}건</strong>
            <span>검색 결과</span>
          </div>
          <div>
            <strong class="is-income">{{ formatCurrency(searchSummary.income) }}</strong>
            <span>수입 합계</span>
          </div>
          <div>
            <strong class="is-expense">{{ formatCurrency(searchSummary.expense) }}</strong>
            <span>지출 합계</span>
          </div>
        </div>

        <div class="sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>날짜</th>
                <th>시각</th>
                <th>제목</th>
                <th>카테고리</th>
                <th>결제수단</th>
                <th>금액</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in searchResults" :key="entry.id">
                <td>{{ formatFullDate(entry.entryDate) }}</td>
                <td>{{ formatTime(entry.entryTime) }}</td>
                <td>{{ entry.title }}</td>
                <td>{{ entry.categoryGroupName }}<template v-if="entry.categoryDetailName"> / {{ entry.categoryDetailName }}</template></td>
                <td>{{ entry.paymentMethodName }}</td>
                <td :class="entry.entryType === 'INCOME' ? 'is-income' : 'is-expense'">
                  {{ formatCurrency(entry.amount) }}
                </td>
              </tr>
              <tr v-if="!searchResults.length">
                <td colspan="6" class="sheet-table__empty">조건에 맞는 거래가 없습니다.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <template v-else-if="route === 'stats-insights'">
      <section class="insight-grid">
        <article class="panel insight-card">
          <p class="insight-card__label">하루 중 지출이 가장 많은 시간대</p>
          <strong>{{ insights.strongestHour.label }}</strong>
          <span>{{ insights.strongestHour.caption }}</span>
        </article>
        <article class="panel insight-card">
          <p class="insight-card__label">7일 중 지출이 가장 많은 요일</p>
          <strong>{{ insights.strongestWeekday.label }}</strong>
          <span>{{ insights.strongestWeekday.caption }}</span>
        </article>
        <article class="panel insight-card">
          <p class="insight-card__label">한 달 중 지출이 가장 큰 주차</p>
          <strong>{{ insights.strongestWeekOfMonth.label }}</strong>
          <span>{{ insights.strongestWeekOfMonth.caption }}</span>
        </article>
        <article class="panel insight-card">
          <p class="insight-card__label">1년 중 지출이 가장 큰 월</p>
          <strong>{{ insights.strongestMonthOfYear.label }}</strong>
          <span>{{ insights.strongestMonthOfYear.caption }}</span>
        </article>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>복합 조회 하이라이트</h2>
            <p>선택한 기간에서 가장 지출이 컸던 날짜를 바로 보여줍니다.</p>
          </div>
        </div>
        <div class="insight-highlight">
          <strong>최대 지출일</strong>
          <span>{{ insights.peakExpenseDay.caption }}</span>
        </div>
      </section>

      <section class="chart-grid chart-grid--insights">
        <BarChartCard
          title="시간대별 지출"
          subtitle="하루 24시간 중 어느 시간대에 지출이 몰렸는지 보여줍니다."
          :items="hourlyChartItems"
          :format-value="formatCurrency"
          empty-text="시간 정보가 있는 지출이 없습니다."
        />
        <BarChartCard
          title="요일별 지출"
          subtitle="한 주 중 어느 요일의 소비가 큰지 보여줍니다."
          :items="weekdayChartItems"
          :format-value="formatCurrency"
          empty-text="요일별 지출 데이터가 없습니다."
        />
        <BarChartCard
          title="주차별 지출"
          subtitle="한 달 안에서 어느 주차에 지출이 몰렸는지 확인합니다."
          :items="weekOfMonthChartItems"
          :format-value="formatCurrency"
          empty-text="주차별 지출 데이터가 없습니다."
        />
        <BarChartCard
          title="월별 지출"
          subtitle="1년 중 소비가 컸던 달을 색상으로 구분합니다."
          :items="monthOfYearChartItems"
          :format-value="formatCurrency"
          empty-text="월별 지출 데이터가 없습니다."
        />
      </section>
    </template>

    <template v-else-if="route === 'stats-compare'">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>지난 기록 비교</h2>
            <p>어제, 지난주, 1개월 전, 1분기 전, 1년 전과 현재 기간을 비교합니다.</p>
          </div>
        </div>

        <div class="compare-cards">
          <article v-for="row in pastComparisons" :key="row.key" class="compare-card">
            <div class="compare-card__head">
              <strong>{{ row.label }}</strong>
              <span>{{ formatDateRange(row.from, row.to) }}</span>
            </div>
            <div class="compare-card__body">
              <div>
                <span>지출</span>
                <strong class="is-expense">{{ formatCurrency(row.overview.expense) }}</strong>
              </div>
              <div>
                <span>변화</span>
                <strong :class="row.deltaExpense > 0 ? 'is-expense' : 'is-income'">
                  {{ row.deltaExpense > 0 ? '+' : '' }}{{ formatCurrency(row.deltaExpense) }}
                </strong>
              </div>
            </div>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>
