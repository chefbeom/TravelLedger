<script setup>
defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
  items: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['navigate'])
</script>

<template>
  <div class="workspace-stack">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>{{ currentUser.displayName }}님, 사용할 기능을 선택해주세요.</h2>
          <p>로그인한 뒤에는 아래 번호 중 하나를 골라 각 기능의 독립 페이지로 바로 들어가는 구조입니다.</p>
        </div>
        <span class="panel__badge">{{ items.length }}개 기능</span>
      </div>
    </section>

    <section class="launcher-grid">
      <article v-for="item in items" :key="item.key" class="launcher-card">
        <div class="launcher-card__number">{{ item.number }}</div>
        <div class="launcher-card__copy">
          <strong>{{ item.title }}</strong>
          <p>{{ item.description }}</p>
        </div>
        <button class="button button--primary launcher-card__action" @click="emit('navigate', item.key)">
          페이지 열기
        </button>
      </article>
    </section>
  </div>
</template>
