<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({ editor: Object, widget: Object, panel: Object, preferences: Object, paymentMethods: Array, busy: Boolean, canDuplicate: Boolean, mobile: Boolean })
const emit = defineEmits(['close', 'widget', 'preference', 'panel', 'duplicate', 'delete', 'move', 'hide'])
const root = ref(null), position = ref({})
const isWidget = computed(() => props.editor?.type === 'widget')
const title = computed(() => isWidget.value ? '위젯 직접 편집' : `${props.editor?.title || '팔레트'} 직접 편집`)
const field = (key, value) => emit('widget', { index: props.editor.index, field: key, value })
const preference = (key, value) => emit('preference', { key, value })
function place() {
  if (!props.editor) return
  const anchor = props.editor?.anchor
  if (!anchor?.isConnected) { emit('close'); return }
  if (window.innerWidth < 760) { position.value = { left: '12px', right: '12px', bottom: '12px' }; return }
  const rect = anchor.getBoundingClientRect(), width = 348
  const height = Math.min(root.value?.offsetHeight || 480, window.innerHeight - 32)
  position.value = { width: `${width}px`, left: `${Math.max(12, Math.min(window.innerWidth - width - 12, rect.right + width + 24 < window.innerWidth ? rect.right + 12 : rect.left - width - 12))}px`, top: `${Math.max(12, Math.min(rect.top, window.innerHeight - height - 12))}px` }
}
function outside(event) {
  if (props.editor && !root.value?.contains(event.target) && !props.editor.anchor?.contains(event.target) && !event.target.closest?.('[data-inline-editor-trigger]')) emit('close')
}
watch(() => props.editor, async value => { if (value) { await nextTick(); place(); root.value?.querySelector('input, select, button')?.focus() } })
watch(() => [props.widget?.cardWidth, props.widget?.cardHeight, props.widget?.kind, props.mobile], () => nextTick(place))
onMounted(() => { window.addEventListener('resize', place); window.addEventListener('scroll', place, true); document.addEventListener('pointerdown', outside, true) })
onBeforeUnmount(() => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true); document.removeEventListener('pointerdown', outside, true) })
</script>

<template>
  <Teleport to="body">
    <section v-if="editor" ref="root" class="calendar-inline-editor" :style="position" role="region" :aria-label="title" data-no-drag="true" @keydown.esc.stop.prevent="emit('close')">
      <header><div><strong>{{ title }}</strong><small>선택한 화면에 바로 반영 · 저장 전 취소 가능</small></div><button type="button" class="button button--ghost" aria-label="직접 편집 닫기" @click="emit('close')">×</button></header>
      <fieldset :disabled="busy">
        <template v-if="isWidget">
          <label><span>위젯 이름</span><input maxlength="80" :value="widget.title || ''" placeholder="자동 제목 사용" @input="field('title', $event.target.value)"></label>
          <label><span>표시할 내용</span><select :value="widget.kind" @change="field('kind', $event.target.value)"><option value="TOTAL">합계</option><option value="PAYMENT_METHOD">결제수단</option><option value="MONTHLY_GOAL">이번달 목표</option><option value="MONTHLY_CUMULATIVE_CHART">월 누적 그래프</option></select></label>
          <label v-if="widget.kind !== 'MONTHLY_GOAL'"><span>집계 기간</span><select :value="widget.period" @change="field('period', $event.target.value)"><option value="YEAR">올해</option><option value="QUARTER">이번 분기</option><option value="MONTH">이번 달</option><option value="WEEK">이번 주</option><option value="DAY">오늘</option></select></label>
          <label v-if="['TOTAL', 'PAYMENT_METHOD'].includes(widget.kind)"><span>집계 기준</span><select :value="widget.amountType" @change="field('amountType', $event.target.value)"><option value="NET">수입-지출</option><option value="INCOME">수입</option><option value="EXPENSE">지출</option></select></label>
          <label v-if="widget.kind === 'PAYMENT_METHOD'"><span>결제수단</span><select :value="widget.paymentMethodId" @change="field('paymentMethodId', $event.target.value)"><option v-for="payment in paymentMethods" :key="payment.id" :value="String(payment.id)">{{ payment.name }}</option></select></label>
          <template v-if="widget.kind === 'MONTHLY_GOAL'"><label><span>월 지출 목표</span><input type="number" min="0" :value="widget.monthlyExpenseTarget || ''" @input="field('monthlyExpenseTarget', $event.target.value)"></label><label><span>1회 지출 기준</span><input type="number" min="0" :value="widget.singleExpenseLimit || ''" @input="field('singleExpenseLimit', $event.target.value)"></label></template>
          <div class="calendar-inline-editor__columns">
            <label><span>글자 크기</span><select :value="widget.textSize" @change="field('textSize', $event.target.value)"><option value="SMALL">작게</option><option value="MEDIUM">기본</option><option value="LARGE">크게</option></select></label>
            <label><span>글자 색상</span><select :value="widget.textColor" @change="field('textColor', $event.target.value)"><option value="DEFAULT">기본</option><option value="MINT">민트</option><option value="SKY">하늘</option><option value="GOLD">오렌지</option><option value="PINK">핑크</option></select></label>
          </div>
          <div class="calendar-inline-editor__columns"><label><span>카드 너비 (px)</span><input type="number" :min="widget.kind === 'MONTHLY_CUMULATIVE_CHART' ? 280 : 200" max="1200" :value="widget.cardWidth || ''" placeholder="자동" @change="field('cardWidth', $event.target.value)"></label><label><span>카드 높이 (px)</span><input type="number" min="160" max="800" :value="widget.cardHeight || ''" placeholder="자동" @change="field('cardHeight', $event.target.value)"></label></div>
          <label class="calendar-inline-editor__check"><input type="checkbox" :checked="widget.showPeriod !== false" @change="field('showPeriod', $event.target.checked)">기간 표시</label>
          <label class="calendar-inline-editor__check"><input type="checkbox" :checked="widget.showDetails !== false" @change="field('showDetails', $event.target.checked)">수입·지출 및 상세 정보 표시</label>
          <template v-if="widget.kind === 'MONTHLY_CUMULATIVE_CHART'">
            <label v-for="option in [{ key: 'showIncomeCumulative', label: '수입 누적선' }, { key: 'showExpenseCumulative', label: '지출 누적선' }, { key: 'comparePreviousPeriod', label: '지난 기간 비교선' }]" :key="option.key" class="calendar-inline-editor__check"><input type="checkbox" :checked="widget[option.key]" @change="field(option.key, $event.target.checked)">{{ option.label }}</label>
            <label><span>그래프 Y축 최대 금액</span><input type="number" min="0" :value="widget.chartMaxAmount || ''" placeholder="자동" @change="field('chartMaxAmount', $event.target.value)"></label>
          </template>
          <div class="calendar-inline-editor__actions"><button type="button" class="button button--secondary" :disabled="editor.index === 0" @click="emit('move', -1)">앞으로</button><button type="button" class="button button--secondary" :disabled="editor.index === 5" @click="emit('move', 1)">뒤로</button><button type="button" class="button button--secondary" :disabled="!canDuplicate" @click="emit('duplicate')">복제</button><button type="button" class="button button--secondary" @click="field('hidden', true); emit('close')">숨기기</button><button type="button" class="button button--danger" @click="emit('delete')">삭제</button></div>
        </template>
        <template v-else>
          <div class="calendar-inline-editor__columns"><label><span>{{ mobile ? '너비 (화면에 맞춤)' : '너비 (9열 기준)' }}</span><input type="number" :disabled="mobile" :min="panel.minW" :max="panel.maxW" :value="mobile ? 9 : panel.w" @change="emit('panel', { id: panel.id, field: 'w', value: Number($event.target.value) })"></label><label><span>높이 (칸)</span><input type="number" :min="panel.minH" :max="panel.maxH" :value="panel.h" @change="emit('panel', { id: panel.id, field: 'h', value: Number($event.target.value) })"></label></div>
          <template v-if="panel.id === 'calendar'">
            <label><span>기본 보기</span><select :value="preferences.defaultWeekMode" @change="preference('defaultWeekMode', $event.target.value)"><option value="month">이번 달</option><option value="current">이번 주</option><option value="previous">이전 주</option></select></label>
            <label><span>표시 기준</span><select :value="preferences.highlightMode" @change="preference('highlightMode', $event.target.value)"><option value="net">수입-지출</option><option value="expense">지출</option><option value="income">수입</option></select></label>
            <label><span>달력 표시 크기</span><select :value="preferences.scalePreset" @change="preference('scalePreset', $event.target.value)"><option value="fit">내 화면에 맞추기</option><option value="default">기본 크기</option></select></label>
            <label><span>거래 시트 보기</span><select :value="preferences.transactionSheetViewMode" @change="preference('transactionSheetViewMode', $event.target.value)"><option value="modal">클릭하여 모달로 보기</option><option value="inline">팔레트로 함께 보기</option></select></label>
          </template>
          <template v-if="panel.id === 'quick-entry'">
            <label class="calendar-inline-editor__check"><input type="checkbox" :checked="preferences.receiptOcrPanelEnabled" @change="preference('receiptOcrPanelEnabled', $event.target.checked)">이미지 자동입력 표시</label>
            <label class="calendar-inline-editor__check"><input type="checkbox" :checked="preferences.shareAfterSaveEnabled" @change="preference('shareAfterSaveEnabled', $event.target.checked)">등록 후 공유 버튼 표시</label>
          </template>
          <button v-if="['aggregate', 'quick-entry'].includes(panel.id)" type="button" class="button button--secondary" @click="emit('hide', panel.id)">이 팔레트 숨기기</button>
          <small>{{ mobile ? '모바일 너비는 화면에 맞춥니다. 윗부분을 잡아 순서를 바꾸고 모서리로 높이를 조절합니다.' : '윗부분을 끌어 이동하고, 오른쪽 아래 모서리로 크기를 조절합니다.' }} 방향키로도 이동·크기를 조절합니다.</small>
        </template>
      </fieldset>
      <footer><button type="button" class="button button--primary" @click="emit('close')">편집 계속하기</button></footer>
    </section>
  </Teleport>
</template>
