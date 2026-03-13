<script setup>
defineProps({
  categories: {
    type: Array,
    default: () => [],
  },
  paymentMethods: {
    type: Array,
    default: () => [],
  },
  groupForm: {
    type: Object,
    required: true,
  },
  detailForm: {
    type: Object,
    required: true,
  },
  paymentForm: {
    type: Object,
    required: true,
  },
  isSubmitting: {
    type: Boolean,
    default: false,
  },
  activeSubmit: {
    type: String,
    default: '',
  },
})

const emit = defineEmits([
  'create-group',
  'create-detail',
  'create-payment',
  'deactivate-group',
  'deactivate-detail',
  'deactivate-payment',
])
</script>

<template>
  <section class="panel">
    <div class="panel__header">
      <div>
        <h2>분류 관리</h2>
        <p>수입/지출 카테고리와 결제수단을 계정별로 정리합니다.</p>
      </div>
    </div>

    <div class="manager-grid">
      <article class="manager-card">
        <h3>대분류 추가</h3>
        <div class="stack-form">
          <select v-model="groupForm.entryType">
            <option value="EXPENSE">지출</option>
            <option value="INCOME">수입</option>
          </select>
          <input v-model="groupForm.name" type="text" placeholder="예: 식비" />
          <input v-model="groupForm.displayOrder" type="number" min="0" placeholder="순서" />
          <button class="button" :disabled="isSubmitting" @click="emit('create-group')">
            {{ isSubmitting && activeSubmit === 'group' ? '추가 중...' : '대분류 추가' }}
          </button>
        </div>
      </article>

      <article class="manager-card">
        <h3>소분류 추가</h3>
        <div class="stack-form">
          <select v-model="detailForm.groupId">
            <option v-for="group in categories" :key="group.id" :value="String(group.id)">
              {{ group.entryType === 'INCOME' ? '수입' : '지출' }} / {{ group.name }}
            </option>
          </select>
          <input v-model="detailForm.name" type="text" placeholder="예: 군것질" />
          <input v-model="detailForm.displayOrder" type="number" min="0" placeholder="순서" />
          <button class="button" :disabled="isSubmitting" @click="emit('create-detail')">
            {{ isSubmitting && activeSubmit === 'detail' ? '추가 중...' : '소분류 추가' }}
          </button>
        </div>
      </article>

      <article class="manager-card">
        <h3>결제수단 추가</h3>
        <div class="stack-form">
          <select v-model="paymentForm.kind">
            <option value="CARD">카드</option>
            <option value="CASH">현금</option>
            <option value="POINT">포인트</option>
            <option value="TRANSFER">계좌이체</option>
            <option value="OTHER">기타</option>
          </select>
          <input v-model="paymentForm.name" type="text" placeholder="예: 토스카드" />
          <input v-model="paymentForm.displayOrder" type="number" min="0" placeholder="순서" />
          <button class="button" :disabled="isSubmitting" @click="emit('create-payment')">
            {{ isSubmitting && activeSubmit === 'payment' ? '추가 중...' : '결제수단 추가' }}
          </button>
        </div>
      </article>
    </div>

    <div class="catalog">
      <article v-for="group in categories" :key="group.id" class="catalog__group">
        <div class="catalog__head">
          <strong>{{ group.entryType === 'INCOME' ? '수입' : '지출' }} / {{ group.name }}</strong>
          <button class="button button--ghost" @click="emit('deactivate-group', group.id)">비활성화</button>
        </div>
        <div class="catalog__chips">
          <button
            v-for="detail in group.details"
            :key="detail.id"
            class="chip chip--neutral"
            @click="emit('deactivate-detail', detail.id)"
          >
            {{ detail.name }}
          </button>
        </div>
      </article>

      <article class="catalog__group">
        <div class="catalog__head">
          <strong>결제수단</strong>
        </div>
        <div class="catalog__chips">
          <button
            v-for="payment in paymentMethods"
            :key="payment.id"
            class="chip chip--neutral"
            @click="emit('deactivate-payment', payment.id)"
          >
            {{ payment.name }} / {{ payment.kind }}
          </button>
        </div>
      </article>
    </div>
  </section>
</template>
