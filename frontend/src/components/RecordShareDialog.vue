<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { createRecordShare, fetchCategories, fetchPaymentMethods, fetchRecordShareGroups } from '../lib/api'
import { buildLedgerShareDraft, buildLedgerShareOverride } from '../lib/recordSharing'

const props = defineProps({ kind: { type: String, required: true }, source: { type: Object, required: true }, currentUserId: { type: [String, Number], default: '' } })
const emit = defineEmits(['close', 'shared'])
const groups = ref([])
const groupId = ref('')
const selected = ref([])
const shareMemo = ref('')
const busy = ref(false)
const error = ref('')
const contentMode = ref('original'), draft = reactive(buildLedgerShareDraft(props.source))
const categories = ref([]), payments = ref([]), catalogsLoaded = ref(false), catalogsLoading = ref(false), catalogError = ref('')
let catalogGeneration = 0
const editing = computed(() => props.kind === 'LEDGER' && contentMode.value === 'edited')
const availableGroups = computed(() => categories.value.filter(item => item.entryType === draft.entryType && (item.active !== false || String(item.id) === String(props.source.categoryGroupId))))
const details = computed(() => (availableGroups.value.find(item => String(item.id) === String(draft.categoryGroupId))?.details || []).filter(item => item.active !== false || String(item.id) === String(props.source.categoryDetailId)))
const availablePayments = computed(() => payments.value.filter(item => item.active !== false || String(item.id) === String(props.source.paymentMethodId)))
const foreign = computed(() => Boolean(draft.foreignCurrencyCode && draft.foreignCurrencyCode !== 'KRW'))
const currencyCodes = computed(() => [...new Set(['KRW', 'USD', 'JPY', 'EUR', 'CNY', 'GBP', 'TWD', 'THB', 'VND', props.source.foreignCurrencyCode].filter(Boolean))])
const estimatedAmount = computed(() => Number.isFinite(Number(draft.foreignAmount) * Number(draft.exchangeRateToKrw)) ? Math.round(Number(draft.foreignAmount) * Number(draft.exchangeRateToKrw)) : '')
const group = computed(() => groups.value.find((item) => String(item.id) === String(groupId.value)))
const recipients = computed(() => (group.value?.members || []).filter((member) => !member.self && String(member.userId) !== String(props.currentUserId)))
const selectedRecipients = computed(() => recipients.value.filter((member) => selected.value.some((id) => String(id) === String(member.userId))))
watch(groupId, () => { selected.value = []; error.value = '' })
watch(editing, value => { error.value = ''; if (value && !catalogsLoaded.value) loadCatalogs() })
watch(() => props.source.id, () => { contentMode.value = 'original'; resetDraft(); shareMemo.value = ''; selected.value = []; error.value = '' })
onBeforeUnmount(() => { catalogGeneration++ })
async function loadCatalogs() {
  if (catalogsLoading.value) return
  const generation = ++catalogGeneration
  catalogsLoading.value = true; catalogError.value = ''
  try {
    const [groups, methods] = await Promise.all([fetchCategories(undefined, { includeInactive: true }), fetchPaymentMethods({ includeInactive: true })])
    if (generation !== catalogGeneration) return
    categories.value = groups; payments.value = methods; catalogsLoaded.value = true
  } catch (ex) { if (generation === catalogGeneration) catalogError.value = ex.message }
  finally { if (generation === catalogGeneration) catalogsLoading.value = false }
}
function resetDraft() { Object.assign(draft, buildLedgerShareDraft(props.source)) }
function changeType() {
  draft.categoryGroupId = availableGroups.value[0]?.id || ''; draft.categoryDetailId = ''
  draft.paymentMethodId = draft.entryType === 'INCOME' ? '' : availablePayments.value[0]?.id || ''
}
onMounted(async () => {
  busy.value = true
  try { groups.value = await fetchRecordShareGroups(); groupId.value = groups.value[0]?.id || '' }
  catch (ex) { error.value = ex.message }
  finally { busy.value = false }
})
async function submit() {
  if (busy.value) return
  if (editing.value && !catalogsLoaded.value) { error.value = '분류·결제수단을 불러온 뒤 다시 요청해 주세요.'; return }
  if (!group.value || !selectedRecipients.value.length || selectedRecipients.value.length > 50) {
    error.value = '공유할 그룹과 받는 사람을 선택해 주세요. 한 번에 최대 50명까지 선택할 수 있습니다.'
    return
  }
  if (shareMemo.value.length > 500) {
    error.value = '공유 메모는 500자까지 입력할 수 있습니다.'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const payload = { kind: props.kind, sourceId: props.source.id, groupId: Number(groupId.value), recipientIds: selectedRecipients.value.map((member) => member.userId), shareMemo: shareMemo.value.trim() || null }
    if (editing.value) payload.ledgerOverride = buildLedgerShareOverride(draft)
    await createRecordShare(payload)
    window.dispatchEvent(new Event('record-shares-changed'))
    emit('shared')
  } catch (ex) { error.value = ex.message }
  finally { busy.value = false }
}
</script>

<template>
  <Teleport to="body">
    <div class="record-share-modal" @click.self="!busy && emit('close')" @keydown.esc="!busy && emit('close')">
      <form class="panel record-share-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="share-record-title" @submit.prevent="submit">
        <div class="panel__header"><h2 id="share-record-title">기록 공유</h2><button class="button button--ghost" type="button" :disabled="busy" @click="emit('close')">닫기</button></div>
        <strong>{{ source.title || source.name }}</strong>
        <p>{{ kind === 'LEDGER' ? '받는 사람이 내용을 확인하고 승인해야 자신의 가계부에 등록됩니다.' : '받는 사람이 수락한 뒤 여행을 읽기 전용으로 볼 수 있습니다.' }}</p>
        <section v-if="kind === 'LEDGER'" class="record-share-section record-share-content" aria-labelledby="share-content-title">
          <h3 id="share-content-title">공유할 거래 내용</h3>
          <div class="record-share-content__modes" role="radiogroup" aria-label="거래 공유 방식">
            <label><input v-model="contentMode" type="radio" value="original" :disabled="busy" />원본 그대로 공유</label>
            <label><input v-model="contentMode" type="radio" value="edited" :disabled="busy" />내용을 수정해서 공유</label>
          </div>
          <p class="record-share-hint">내 거래 원본은 변경되지 않습니다. 수정 내용은 이번에 보내는 공유 내역에만 적용됩니다.</p>
          <template v-if="editing">
            <p v-if="catalogsLoading" role="status">내 분류·결제수단을 불러오는 중입니다…</p>
            <div v-if="catalogError" class="record-share-error" role="alert">{{ catalogError }} <button class="button" type="button" @click="loadCatalogs">다시 불러오기</button></div>
            <fieldset class="record-share-content__fields" :disabled="busy || !catalogsLoaded">
              <legend class="record-share-hint">받는 사람에게 전달할 거래</legend>
              <label class="field"><span>공유 제목</span><input v-model="draft.title" required maxlength="120" /></label>
              <div class="record-share-form-grid"><label class="field"><span>공유 날짜</span><input v-model="draft.entryDate" type="date" required /></label><label class="field"><span>공유 시간</span><input v-model="draft.entryTime" type="time" step="1" /></label></div>
              <div class="record-share-form-grid"><label class="field"><span>공유 수입/지출</span><select v-model="draft.entryType" @change="changeType"><option value="EXPENSE">지출</option><option value="INCOME">수입</option></select></label><label class="field"><span>공유 통화</span><select v-model="draft.foreignCurrencyCode"><option v-for="code in currencyCodes" :key="code" :value="code">{{ code }}</option></select></label></div>
              <label v-if="!foreign" class="field"><span>공유 금액 (원)</span><input v-model="draft.amount" type="number" required min="0.01" max="9999999999999.99" step="0.01" /></label>
              <template v-else><div class="record-share-form-grid"><label class="field"><span>공유 외화 금액</span><input v-model="draft.foreignAmount" type="number" required min="0.0001" step="0.0001" /></label><label class="field"><span>공유 적용 환율</span><input v-model="draft.exchangeRateToKrw" type="number" required min="0.000001" step="0.000001" /></label></div><p class="record-share-hint">예상 원화 금액 {{ estimatedAmount === '' ? '—' : estimatedAmount.toLocaleString('ko-KR') }}원 · 외화 금액과 적용 환율로 계산합니다.</p></template>
              <div class="record-share-form-grid"><label class="field"><span>공유 대분류</span><select v-model="draft.categoryGroupId" required @change="draft.categoryDetailId = ''"><option value="" disabled>선택하세요</option><option v-for="item in availableGroups" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><label class="field"><span>공유 분류</span><select v-model="draft.categoryDetailId"><option value="">미분류</option><option v-for="item in details" :key="item.id" :value="item.id">{{ item.name }}</option></select></label></div>
              <label v-if="draft.entryType === 'EXPENSE'" class="field"><span>공유 결제수단</span><select v-model="draft.paymentMethodId" required><option value="" disabled>선택하세요</option><option v-for="item in availablePayments" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
              <label class="field"><span>공유 거래 메모</span><textarea v-model="draft.memo" maxlength="500" rows="3" /></label>
              <button class="button button--ghost" type="button" @click="resetDraft">원본 내용으로 되돌리기</button>
            </fieldset>
          </template>
        </section>
        <label class="field"><span>공유 그룹</span><select v-model="groupId" aria-label="공유 그룹" :disabled="busy"><option v-for="item in groups" :key="item.id" :value="item.id">{{ item.name }} · {{ item.members.length }}명</option></select></label>
        <p v-if="!groups.length && !busy" class="panel__empty">기록 공유 화면에서 가족·친구·모임 그룹을 먼저 만들어 주세요.</p>
        <section v-if="group" class="record-share-section" aria-labelledby="share-recipients-title">
          <div class="record-share-section__header"><h3 id="share-recipients-title">받는 사람 선택</h3><span class="record-share-state">{{ selectedRecipients.length }} / {{ recipients.length }}명 선택</span></div>
          <p class="record-share-hint">선택한 사람에게만 요청합니다. 그룹 전체에 자동으로 공유하지 않습니다.</p>
          <div class="record-share-selection-actions"><button type="button" class="button button--ghost" :disabled="busy || !recipients.length || recipients.length > 50" @click="selected = recipients.map((member) => member.userId)">전체 선택</button><button type="button" class="button button--ghost" :disabled="busy || !selected.length" @click="selected = []">선택 해제</button></div>
          <div class="record-share-recipient-grid">
            <label v-for="member in recipients" :key="member.userId" :class="['record-share-member', { 'is-selected': selected.includes(member.userId) }]"><input v-model="selected" type="checkbox" :value="member.userId" :aria-label="`${member.displayName} (${member.loginId})에게 공유`" :disabled="busy || selectedRecipients.length >= 50 && !selected.includes(member.userId)" /><span><strong>{{ member.displayName }}</strong><small>{{ member.loginId }}</small></span><small class="record-share-member__state">{{ selected.includes(member.userId) ? '선택됨' : '미선택' }}</small></label>
          </div>
          <p v-if="!recipients.length" class="panel__empty">선택할 다른 멤버가 없습니다. 공유 그룹 관리에서 멤버를 추가해 주세요.</p>
          <p v-if="recipients.length > 50" class="record-share-hint">한 번에 최대 50명까지 선택할 수 있습니다.</p>
        </section>
        <label v-if="kind === 'LEDGER'" class="field record-share-memo-field">
          <span>공유 메모 (선택)</span>
          <textarea v-model="shareMemo" maxlength="500" rows="3" :disabled="busy" placeholder="예: 당신 카드로 결제한 저녁 식사예요." aria-label="공유 메모 (선택)" aria-describedby="share-memo-help share-memo-count" />
          <small id="share-memo-help" class="record-share-hint">선택한 받는 사람에게 전달됩니다. 거래 메모와 별도로 표시되며, 내 가계부에 자동으로 복사되지 않습니다.</small>
          <small id="share-memo-count" class="record-share-memo-count">{{ shareMemo.length }} / 500자</small>
        </label>
        <p v-if="error" role="alert">{{ error }}</p>
        <p v-if="selectedRecipients.length" class="record-share-hint" aria-live="polite">받는 사람: {{ selectedRecipients.map((member) => `${member.displayName} (${member.loginId})`).join(', ') }}</p>
        <button class="button button--primary" type="submit" :disabled="busy || !group || !selectedRecipients.length || editing && !catalogsLoaded">{{ busy ? '처리 중…' : `${selectedRecipients.length}명에게 공유 요청` }}</button>
      </form>
    </div>
  </Teleport>
</template>
<style scoped>
.record-share-content { display:grid; gap:12px; }
.record-share-content h3 { margin:0; }
.record-share-content__modes { display:flex; gap:12px; flex-wrap:wrap; }
.record-share-content__modes label { display:flex; align-items:center; gap:6px; cursor:pointer; font-size:.9rem; }
.record-share-content__modes input { width:18px !important; height:18px !important; min-width:18px !important; margin:0; }
.record-share-content__fields { display:grid; gap:12px; min-width:0; padding:0; margin:0; border:0; }
.record-share-content__fields legend { padding:0 0 10px; }
.record-share-content__fields .field { min-width:0; }
.record-share-content__fields :is(input,select,textarea) { width:100%; min-width:0 !important; max-width:100%; box-sizing:border-box; }
.record-share-content__fields .button { justify-self:start; }
:global([data-theme='toss']) .record-share-content__fields input:is([type='date'],[type='time']) { color-scheme:dark; }
</style>
