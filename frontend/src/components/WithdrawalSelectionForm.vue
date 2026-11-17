<script setup>
import { computed, reactive, watch } from 'vue'

const props = defineProps({
  preview: { type: Object, required: true },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' },
})
const emit = defineEmits(['submit'])
const form = reactive({ familyOwners: {}, groupOwners: {}, travelPlanOwners: {}, secondaryPin: '', acknowledged: false })

watch(() => props.preview, () => {
  form.familyOwners = {}
  form.groupOwners = {}
  form.travelPlanOwners = {}
  form.secondaryPin = ''
  form.acknowledged = false
}, { immediate: true })

const families = computed(() => Array.isArray(props.preview?.requiredFamilyTransfers) ? props.preview.requiredFamilyTransfers : [])
const groups = computed(() => Array.isArray(props.preview?.requiredShareGroupTransfers) ? props.preview.requiredShareGroupTransfers : [])
const plans = computed(() => Array.isArray(props.preview?.requiredTravelPlanTransfers) ? props.preview.requiredTravelPlanTransfers : [])
const members = value => Array.isArray(value) ? value : []
const matches = (selected, candidates, requireEligible) => selected !== undefined && selected !== null && selected !== ''
  && members(candidates).some(member => member && String(member.userId) === String(selected) && (!requireEligible || member.eligible === true))
const choicesValid = computed(() => Array.isArray(props.preview?.requiredFamilyTransfers)
  && Array.isArray(props.preview?.requiredShareGroupTransfers) && Array.isArray(props.preview?.requiredTravelPlanTransfers)
  && Number.isInteger(props.preview?.foreignTravelMedia?.planCount) && props.preview.foreignTravelMedia.planCount >= 0
  && Number.isInteger(props.preview?.foreignTravelMedia?.mediaCount) && props.preview.foreignTravelMedia.mediaCount >= 0
  && families.value.every(item => matches(form.familyOwners[item.categoryId], item.eligibleMembers, false))
  && groups.value.every(item => matches(form.groupOwners[item.groupId], item.members, true))
  && plans.value.every(item => matches(form.travelPlanOwners[item.planId], item.members, true)))
const canSubmit = computed(() => props.preview?.withdrawalAvailable === true && choicesValid.value
  && form.acknowledged && /^\d{8}$/.test(form.secondaryPin) && !props.busy)

function submit() {
  if (!canSubmit.value) return
  emit('submit', {
    secondaryPin: form.secondaryPin,
    familyOwners: { ...form.familyOwners },
    groupOwners: { ...form.groupOwners },
    travelPlanOwners: { ...form.travelPlanOwners },
    acknowledged: form.acknowledged,
  })
  form.secondaryPin = ''
}
</script>

<template>
  <form v-if="preview.withdrawalAvailable" class="stack-form" @submit.prevent="submit">
    <div v-for="category in families" :key="`family-${category.categoryId}`" class="field">
      <label class="field__label" :for="`withdraw-family-${category.categoryId}`">{{ category.categoryName }}의 새 소유자</label>
      <select :id="`withdraw-family-${category.categoryId}`" v-model="form.familyOwners[category.categoryId]" :disabled="busy || !members(category.eligibleMembers).length">
        <option :value="undefined">이전받을 멤버를 선택하세요</option>
        <option v-for="member in members(category.eligibleMembers)" :key="member.userId" :value="member.userId">{{ member.displayName }} ({{ member.loginId }})</option>
      </select>
      <small v-if="!members(category.eligibleMembers).length">이전할 활성 멤버가 없습니다. 공유 설정을 확인해 주세요.</small>
    </div>
    <div v-for="group in groups" :key="`group-${group.groupId}`" class="field">
      <label class="field__label" :for="`withdraw-group-${group.groupId}`">{{ group.groupName }}의 새 소유자</label>
      <select :id="`withdraw-group-${group.groupId}`" v-model="form.groupOwners[group.groupId]" :disabled="busy || !members(group.members).length">
        <option :value="undefined">이전받을 멤버를 선택하세요</option>
        <option v-for="member in members(group.members)" :key="member.userId" :value="member.userId" :disabled="!member.eligible">
          {{ member.displayName }} ({{ member.loginId }}){{ member.eligible ? '' : ` — ${member.unavailableReason}` }}
        </option>
      </select>
    </div>
    <div v-for="plan in plans" :key="`plan-${plan.planId}`" class="field">
      <label class="field__label" :for="`withdraw-plan-${plan.planId}`">{{ plan.planName }}의 새 소유자</label>
      <select :id="`withdraw-plan-${plan.planId}`" v-model="form.travelPlanOwners[plan.planId]" :disabled="busy || !members(plan.members).length">
        <option :value="undefined">이전받을 멤버를 선택하세요</option>
        <option v-for="member in members(plan.members)" :key="member.userId" :value="member.userId" :disabled="!member.eligible">
          {{ member.displayName }} ({{ member.loginId }}){{ member.eligible ? '' : ` — ${member.unavailableReason}` }}
        </option>
      </select>
      <small v-if="!members(plan.members).length && plan.publiclyShared">먼저 이전받을 회원에게 이 여행을 공유해 주세요.</small>
    </div>
    <p v-if="!choicesValid" class="feedback feedback--error" role="status">공유 항목마다 이전 가능한 소유자를 선택해 주세요. 이전 대상이 없으면 먼저 공유 설정을 확인해 주세요.</p>
    <label class="field"><span class="field__label">최종 확인용 2차 비밀번호</span>
      <input v-model.trim="form.secondaryPin" type="password" inputmode="numeric" autocomplete="one-time-code" maxlength="8" :disabled="busy" />
    </label>
    <label><input v-model="form.acknowledged" type="checkbox" :disabled="busy" /> 안내된 자료 정리와 공유 항목 이전에 동의합니다.</label>
    <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
    <button class="button button--danger" type="submit" :disabled="!canSubmit">{{ busy ? '요청 중...' : '탈퇴 요청' }}</button>
  </form>
</template>
