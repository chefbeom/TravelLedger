<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { PERSONALIZATION_SECTIONS } from '../lib/calendarPersonalization'
const props = defineProps({
  open: Boolean, section: { type: String, default: 'dashboard' }, busy: Boolean, loading: Boolean,
  dirty: Boolean, error: String, notice: String, conflict: Boolean, canSave: Boolean,
  preferences: { type: Object, default: () => ({}) }, widgets: { type: Array, default: () => [] },
  cards: { type: Array, default: () => [] }, selectedIndex: { type: Number, default: 0 },
  panels: { type: Array, default: () => [] }, paymentMethods: { type: Array, default: () => [] },
})
const emit = defineEmits(['section', 'close', 'save', 'reload', 'preference', 'widget', 'select-widget', 'add-widget', 'duplicate-widget', 'delete-widget', 'move-widget', 'panel', 'edit-layout', 'reset'])
const dialog = ref(null), panelId = ref('calendar')
const widget = computed(() => props.widgets[props.selectedIndex] || {})
const cardColumnOptions = computed(() => Array.from({ length: widget.value.kind === 'MONTHLY_CUMULATIVE_CHART' ? 8 : 9 }, (_, index) => index + (widget.value.kind === 'MONTHLY_CUMULATIVE_CHART' ? 2 : 1)))
const card = computed(() => props.cards[props.selectedIndex])
const panel = computed(() => props.panels.find(item => item.id === panelId.value) || {})
const panelWidths = computed(() => Array.from({ length: (panel.value.maxW || 9) - (panel.value.minW || 1) + 1 }, (_, index) => index + (panel.value.minW || 1)))
const panelNames = { aggregate: '집계 영역', calendar: '달력', 'quick-entry': '빠른 거래 입력', sheet: '거래 시트' }
const field = (key, value) => emit('widget', { index: props.selectedIndex, field: key, value })
const preference = (key, value) => emit('preference', { key, value })
const amount = value => new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(Number(value) || 0)
let previousFocus
watch(() => props.open, async open => {
  if (open) { previousFocus = document.activeElement; await nextTick(); dialog.value?.querySelector('[data-initial-focus]')?.focus() }
  else previousFocus?.focus?.()
})
function keydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); if (!props.busy) emit('close'); return }
  if (event.key !== 'Tab') return
  const fields = [...dialog.value.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled), summary, [tabindex="0"]')].filter(element => element.getClientRects().length)
  const first = fields[0], last = fields.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
function switchTab(event, index) {
  if (!['ArrowDown', 'ArrowUp', 'ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? PERSONALIZATION_SECTIONS.length - 1
    : (index + (['ArrowDown', 'ArrowRight'].includes(event.key) ? 1 : -1) + PERSONALIZATION_SECTIONS.length) % PERSONALIZATION_SECTIONS.length
  emit('section', PERSONALIZATION_SECTIONS[next].id)
  nextTick(() => dialog.value.querySelector(`#personalization-tab-${PERSONALIZATION_SECTIONS[next].id}`)?.focus())
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="personalization-backdrop" @click.self="!busy && emit('close')">
      <section ref="dialog" class="personalization-dialog" role="dialog" aria-modal="true" aria-labelledby="personalization-title" :aria-busy="busy || loading" @keydown="keydown">
        <header class="personalization-header">
          <div><small>나의 달력 가계부</small><h2 id="personalization-title">개인화 설정</h2><p>화면에서 미리 확인하고, 저장할 때 한 번에 적용합니다.</p></div>
          <button data-initial-focus class="button button--ghost" :disabled="busy" aria-label="개인화 설정 닫기" @click="emit('close')">닫기</button>
        </header>
        <div class="personalization-body">
          <nav class="personalization-nav" role="tablist" aria-label="개인화 설정 분류">
            <button v-for="(item, index) in PERSONALIZATION_SECTIONS" :id="`personalization-tab-${item.id}`" :key="item.id" role="tab" :aria-selected="section === item.id" aria-controls="personalization-section" :tabindex="section === item.id ? 0 : -1" :class="{ 'is-active': section === item.id }" @click="emit('section', item.id)" @keydown="switchTab($event, index)"><strong>{{ item.label }}</strong><small>{{ item.description }}</small></button>
          </nav>
          <div id="personalization-section" class="personalization-content" role="tabpanel" :aria-labelledby="`personalization-tab-${section}`">
            <p v-if="loading" role="status" class="personalization-notice">저장된 개인 설정을 확인하고 있습니다.</p>
            <div v-if="notice" role="status" class="personalization-notice"><p>{{ notice }}</p><button class="button button--secondary" :disabled="busy" @click="emit('reload')">서버 설정으로 다시 열기</button></div>
            <div v-if="error" role="alert" class="personalization-error"><p>{{ error }}</p><button v-if="!busy" class="button button--secondary" @click="emit('reload')">{{ conflict ? '최신 설정으로 다시 열기' : '설정 다시 확인' }}</button><small>초안은 유지됩니다. 최신 설정으로 다시 열 때에는 변경사항을 버릴지 확인합니다.</small></div>
            <fieldset class="personalization-fields" :disabled="busy || loading">
            <template v-if="section === 'dashboard'">
              <h3>화면 구성</h3><p class="personalization-description">표시 여부와 크기를 관리합니다. 위치와 크기를 직접 조절하려면 배치 편집을 사용하세요.</p>
              <div class="personalization-switch-list">
                <label><span><strong>집계 영역</strong><small>위젯 설정을 지우지 않고 표시만 변경</small></span><input role="switch" type="checkbox" :checked="preferences.aggregatePanelEnabled" @change="preference('aggregatePanelEnabled', $event.target.checked)"></label>
                <label><span><strong>빠른 거래 입력</strong><small>입력 폼 표시·숨김</small></span><input role="switch" type="checkbox" :checked="preferences.quickEntryPanelEnabled !== false" @change="preference('quickEntryPanelEnabled', $event.target.checked)"></label>
              </div>
              <button class="button button--secondary" @click="emit('edit-layout')">화면에서 배치 직접 편집</button>
              <div class="personalization-form-grid">
                <label class="personalization-field"><span>편집할 영역</span><select v-model="panelId"><option v-for="item in panels" :key="item.id" :value="item.id">{{ panelNames[item.id] }}</option></select></label>
                <label class="personalization-field"><span>영역 너비</span><select :value="panel.w" @change="emit('panel', { id: panelId, field: 'w', value: Number($event.target.value) })"><option v-for="width in panelWidths" :key="width" :value="width">{{ width }} / 9열</option></select></label>
              </div>
              <details class="personalization-advanced"><summary>고급 배치 좌표</summary><div class="personalization-form-grid"><label v-for="key in ['x', 'y', 'h']" :key="key" class="personalization-field"><span>{{ { x: '가로 위치 (0부터)', y: '세로 위치 (0부터)', h: '영역 높이' }[key] }}</span><input type="number" :min="key === 'h' ? panel.minH : 0" :max="key === 'x' ? 8 : key === 'h' ? panel.maxH : 200" :value="panel[key]" @change="emit('panel', { id: panelId, field: key, value: Number($event.target.value) })"></label></div><p>9열 격자 좌표를 유지합니다. 화면 크기 변경만으로 저장한 배치를 덮어쓰지 않습니다.</p></details>
            </template>
            <template v-else-if="section === 'calendar'">
              <h3>기본 달력 표시</h3><p class="personalization-description">상단에서 잠깐 바꾼 조회 필터는 기본 설정을 덮어쓰지 않습니다.</p>
              <div class="personalization-form-grid">
                <label class="personalization-field"><span>기본 보기</span><select :value="preferences.defaultWeekMode || 'month'" @change="preference('defaultWeekMode', $event.target.value)"><option value="month">이번달 보기</option><option value="current">이번 주 보기</option><option value="previous">이전 주 보기</option></select></label>
                <label class="personalization-field"><span>기본 표시 기준</span><select :value="preferences.highlightMode" @change="preference('highlightMode', $event.target.value)"><option value="net">수입-지출 보기</option><option value="expense">지출만 보기</option><option value="income">수입만 보기</option></select></label>
                <label class="personalization-field"><span>달력 크기</span><select :value="preferences.scalePreset" @change="preference('scalePreset', $event.target.value)"><option value="fit">내 화면에 맞추기</option><option value="default">지금처럼 보기</option></select></label>
                <label class="personalization-field"><span>거래 시트 표시 방식</span><select :value="preferences.transactionSheetViewMode" @change="preference('transactionSheetViewMode', $event.target.value)"><option value="modal">모달창으로 보기</option><option value="inline">페이지에 함께 보기</option></select></label>
              </div>
            </template>
            <template v-else-if="section === 'widgets'">
              <div class="personalization-section-title"><div><h3>집계 위젯</h3><p class="personalization-description">선택한 위젯 하나만 편집합니다. 최대 6개 구성은 유지됩니다.</p></div><button class="button button--secondary" :disabled="!widgets.some(item => item.kind === 'NONE')" @click="emit('add-widget')">위젯 추가</button></div>
              <div class="personalization-widget-list" aria-label="집계 위젯 선택"><button v-for="(item, index) in widgets" :key="item.id || index" :aria-pressed="selectedIndex === index" :class="{ 'is-active': selectedIndex === index }" @click="emit('select-widget', index)"><strong>{{ index + 1 }}. {{ item.title || cards[index]?.title || '빈 슬롯' }}</strong><small>{{ item.kind === 'NONE' ? '사용 안 함' : item.hidden ? '숨김' : '표시 중' }}</small></button></div>
              <div class="personalization-preview" aria-label="선택 위젯 실시간 미리보기" :data-text-size="widget.textSize" :data-text-color="widget.textColor">
                <small>실시간 미리보기 · {{ card?.periodLabel || '위젯을 선택하세요' }}</small><strong>{{ widget.title || card?.title }}</strong>
                <b>{{ widget.kind === 'NONE' ? '표시할 위젯을 선택하세요' : amount(card?.chart?.headlineAmount ?? card?.goal?.expense ?? card?.totalAmount) }}</b>
                <span v-if="widget.kind !== 'NONE'">수입 {{ amount(card?.overview?.income) }} · 지출 {{ amount(card?.overview?.expense) }}</span>
                <svg v-if="card?.chart" viewBox="0 0 448 200" class="personalization-preview-chart" role="img" aria-label="선택한 누적 그래프 미리보기">
                  <polyline v-if="widget.comparePreviousPeriod && widget.showIncomeCumulative" :points="card.chart.previousIncomePoints" class="personalization-preview-chart__income personalization-preview-chart__previous" />
                  <polyline v-if="widget.comparePreviousPeriod && widget.showExpenseCumulative" :points="card.chart.previousExpensePoints" class="personalization-preview-chart__expense personalization-preview-chart__previous" />
                  <polyline v-if="widget.showIncomeCumulative" :points="card.chart.incomePoints" class="personalization-preview-chart__income" />
                  <polyline v-if="widget.showExpenseCumulative" :points="card.chart.expensePoints" class="personalization-preview-chart__expense" />
                </svg>
                <small v-if="widget.hidden">저장 후 대시보드에서는 숨겨집니다.</small>
              </div>
              <div class="personalization-form-grid" :key="selectedIndex">
                <label class="personalization-field personalization-field--wide"><span>위젯 이름</span><input maxlength="80" :value="widget.title || ''" :placeholder="card?.title || '위젯 이름'" @input="field('title', $event.target.value)"></label>
                <label class="personalization-field"><span>위젯 유형</span><select :value="widget.kind" @change="field('kind', $event.target.value)"><option value="NONE">사용 안 함</option><option value="TOTAL">합계</option><option value="PAYMENT_METHOD">결제수단</option><option value="MONTHLY_GOAL">이번달 목표</option><option value="MONTHLY_CUMULATIVE_CHART">월 누적 그래프</option></select></label>
                <label v-if="widget.kind !== 'NONE' && widget.kind !== 'MONTHLY_GOAL'" class="personalization-field"><span>집계 기간</span><select :value="widget.period" @change="field('period', $event.target.value)"><option value="YEAR">올해</option><option value="QUARTER">이번 분기</option><option value="MONTH">이번 달</option><option value="WEEK">이번 주</option><option value="DAY">오늘</option></select></label>
                <label v-if="['TOTAL', 'PAYMENT_METHOD'].includes(widget.kind)" class="personalization-field"><span>집계 기준</span><select :value="widget.amountType" @change="field('amountType', $event.target.value)"><option value="NET">전체</option><option value="INCOME">수입</option><option value="EXPENSE">지출</option></select></label>
                <label v-if="widget.kind === 'PAYMENT_METHOD'" class="personalization-field"><span>결제수단</span><select :value="widget.paymentMethodId" @change="field('paymentMethodId', $event.target.value)"><option v-for="payment in paymentMethods" :key="payment.id" :value="String(payment.id)">{{ payment.name }}</option></select></label>
                <template v-if="widget.kind === 'MONTHLY_GOAL'"><label class="personalization-field"><span>월 지출 목표</span><input type="number" min="0" :value="widget.monthlyExpenseTarget || ''" @input="field('monthlyExpenseTarget', $event.target.value)"></label><label class="personalization-field"><span>1회 지출 기준</span><input type="number" min="0" :value="widget.singleExpenseLimit || ''" @input="field('singleExpenseLimit', $event.target.value)"></label></template>
                <template v-if="widget.kind === 'MONTHLY_CUMULATIVE_CHART'"><label class="personalization-field"><span>Y축 최대 금액</span><input type="number" min="0" placeholder="자동" :value="widget.chartMaxAmount || ''" @input="field('chartMaxAmount', $event.target.value)"></label><label v-for="option in [{ key: 'showIncomeCumulative', label: '수입 누적' }, { key: 'showExpenseCumulative', label: '지출 누적' }, { key: 'comparePreviousPeriod', label: '지난 기간 비교' }]" :key="option.key" class="personalization-check"><input type="checkbox" :checked="widget[option.key]" @change="field(option.key, $event.target.checked)">{{ option.label }}</label></template>
                <template v-if="widget.kind !== 'NONE'">
                  <label class="personalization-field"><span>카드 너비 (칸)</span><select :value="widget.cardColumns || 0" @change="field('cardColumns', Number($event.target.value))"><option :value="0">기존 너비를 칸에 맞춤</option><option v-for="columns in cardColumnOptions" :key="columns" :value="columns">{{ columns }} / 9열</option></select></label>
                  <label class="personalization-field"><span>카드 높이 (칸)</span><select :value="widget.cardRows || 0" @change="field('cardRows', Number($event.target.value))"><option :value="0">기존 높이를 칸에 맞춤</option><option v-for="rows in 12" :key="rows" :value="rows">{{ rows }}행</option></select></label>
                  <label class="personalization-field"><span>글자 크기</span><select :value="widget.textSize" @change="field('textSize', $event.target.value)"><option value="SMALL">작게</option><option value="MEDIUM">기본</option><option value="LARGE">크게</option></select></label><label class="personalization-field"><span>글자 색상</span><select :value="widget.textColor" @change="field('textColor', $event.target.value)"><option value="DEFAULT">기본</option><option value="MINT">민트</option><option value="SKY">하늘</option><option value="GOLD">오렌지</option><option value="PINK">핑크</option></select></label><label class="personalization-check"><input role="switch" type="checkbox" :checked="!widget.hidden" @change="field('hidden', !$event.target.checked)">대시보드에 표시</label>
                </template>
              </div>
              <div class="personalization-widget-actions"><button class="button button--secondary" :disabled="selectedIndex === 0" @click="emit('move-widget', -1)">앞으로 이동</button><button class="button button--secondary" :disabled="selectedIndex === widgets.length - 1" @click="emit('move-widget', 1)">뒤로 이동</button><button class="button button--secondary" :disabled="widget.kind === 'NONE' || !widgets.some(item => item.kind === 'NONE')" @click="emit('duplicate-widget')">위젯 복제</button><button class="button button--danger" :disabled="widget.kind === 'NONE'" @click="emit('delete-widget')">위젯 삭제</button></div>
            </template>
            <template v-else-if="section === 'input'">
              <h3>거래 입력</h3><div class="personalization-switch-list">
                <label><span><strong>빠른 거래 입력 표시</strong><small>입력 폼의 표시 여부만 변경합니다.</small></span><input role="switch" type="checkbox" :checked="preferences.quickEntryPanelEnabled !== false" @change="preference('quickEntryPanelEnabled', $event.target.checked)"></label>
                <label><span><strong>거래 이미지 자동입력 표시</strong><small>AI 분석 기능과 기존 이력은 그대로 유지됩니다.</small></span><input role="switch" type="checkbox" :checked="preferences.receiptOcrPanelEnabled" @change="preference('receiptOcrPanelEnabled', $event.target.checked)"></label>
                <label><span><strong>등록 후 공유 버튼 표시</strong><small>켜도 자동으로 공유되지 않습니다. 받는 사람은 직접 선택합니다.</small></span><input role="switch" type="checkbox" :checked="preferences.shareAfterSaveEnabled" @change="preference('shareAfterSaveEnabled', $event.target.checked)"></label>
              </div><p class="personalization-description">날짜·결제수단·분류·시간 입력과 AI 요청사항 등 거래 작성 기능은 기존 입력 화면에서 그대로 사용합니다.</p>
            </template>
            <template v-else>
              <h3>기본값 복원</h3><p class="personalization-description">초기화 범위를 선택하세요. 거래 내역·이미지 분석 이력·공유 내역은 삭제하지 않습니다. 복원도 저장 전까지 초안입니다.</p>
              <div class="personalization-reset-list"><article><strong>추천 기본 배치</strong><p>영역 위치와 크기만 9×8 추천 배치로 복원합니다.</p><button class="button button--secondary" @click="emit('reset', 'layout')">배치만 초기화</button></article><article><strong>달력·입력 표시 기본값</strong><p>집계 위젯 내용과 저장된 배치는 유지합니다.</p><button class="button button--secondary" @click="emit('reset', 'preferences')">표시 설정만 초기화</button></article><article><strong>집계 위젯 기본 구성</strong><p>6개 슬롯의 집계 구성을 기본값으로 복원합니다.</p><button class="button button--secondary" @click="emit('reset', 'widgets')">집계 설정만 초기화</button></article></div>
            </template>
            </fieldset>
          </div>
        </div>
        <footer class="personalization-footer"><span role="status">{{ busy ? '개인 설정 저장 중…' : dirty ? '저장하지 않은 변경사항이 있습니다.' : '변경사항이 없습니다.' }}</span><div><button class="button button--secondary" :disabled="busy" @click="emit('close')">취소</button><button class="button button--primary" :disabled="!dirty || !canSave || busy || loading" @click="emit('save')">{{ busy ? '저장 중…' : '변경사항 저장' }}</button></div></footer>
      </section>
    </div>
  </Teleport>
</template>
