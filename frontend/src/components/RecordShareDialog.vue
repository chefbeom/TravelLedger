<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { createRecordShare, fetchRecordShareGroups } from '../lib/api'

const props = defineProps({ kind: { type: String, required: true }, source: { type: Object, required: true }, currentUserId: { type: [String, Number], default: '' } })
const emit = defineEmits(['close', 'shared'])
const groups = ref([])
const groupId = ref('')
const selected = ref([])
const shareMemo = ref('')
const busy = ref(false)
const error = ref('')
const group = computed(() => groups.value.find((item) => String(item.id) === String(groupId.value)))
const recipients = computed(() => (group.value?.members || []).filter((member) => !member.self && String(member.userId) !== String(props.currentUserId)))
const selectedRecipients = computed(() => recipients.value.filter((member) => selected.value.some((id) => String(id) === String(member.userId))))
watch(groupId, () => { selected.value = []; error.value = '' })
onMounted(async () => {
  busy.value = true
  try { groups.value = await fetchRecordShareGroups(); groupId.value = groups.value[0]?.id || '' }
  catch (ex) { error.value = ex.message }
  finally { busy.value = false }
})
async function submit() {
  if (busy.value) return
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
    await createRecordShare({ kind: props.kind, sourceId: props.source.id, groupId: Number(groupId.value), recipientIds: selectedRecipients.value.map((member) => member.userId), shareMemo: shareMemo.value.trim() || null })
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
        <button class="button button--primary" type="submit" :disabled="busy || !group || !selectedRecipients.length">{{ busy ? '처리 중…' : `${selectedRecipients.length}명에게 공유 요청` }}</button>
      </form>
    </div>
  </Teleport>
</template>
