<script setup>
import { computed, onMounted, ref } from 'vue'
import { createRecordShare, fetchRecordShareGroups } from '../lib/api'

const props = defineProps({ kind: { type: String, required: true }, source: { type: Object, required: true }, currentUserId: { type: [String, Number], default: '' } })
const emit = defineEmits(['close', 'shared'])
const groups = ref([])
const groupId = ref('')
const selected = ref([])
const busy = ref(false)
const error = ref('')
const group = computed(() => groups.value.find((item) => String(item.id) === String(groupId.value)))
const recipients = computed(() => (group.value?.members || []).filter((member) => !member.self && String(member.userId) !== String(props.currentUserId)))
onMounted(async () => {
  busy.value = true
  try { groups.value = await fetchRecordShareGroups(); groupId.value = groups.value[0]?.id || '' }
  catch (ex) { error.value = ex.message }
  finally { busy.value = false }
})
async function submit() {
  busy.value = true
  error.value = ''
  try {
    await createRecordShare({ kind: props.kind, sourceId: props.source.id, groupId: Number(groupId.value), recipientIds: selected.value })
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
        <label class="field"><span>공유 그룹</span><select v-model="groupId" :disabled="busy" @change="selected = []"><option v-for="item in groups" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <p v-if="!groups.length && !busy" class="panel__empty">기록 공유 화면에서 가족·친구·모임 그룹을 먼저 만들어 주세요.</p>
        <div class="record-share-members">
          <label v-for="member in recipients" :key="member.userId" class="record-share-member"><input v-model="selected" type="checkbox" :value="member.userId" :disabled="busy" /><span>{{ member.displayName }} <small>{{ member.loginId }}</small></span></label>
        </div>
        <p v-if="error" role="alert">{{ error }}</p>
        <button class="button button--primary" type="submit" :disabled="busy || !selected.length">{{ busy ? '처리 중…' : `${selected.length}명에게 공유 요청` }}</button>
      </form>
    </div>
  </Teleport>
</template>
