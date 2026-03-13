<script setup>
defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
  navGroups: {
    type: Array,
    default: () => [],
  },
  activeRoute: {
    type: String,
    required: true,
  },
})

const emit = defineEmits(['navigate', 'logout'])
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar__brand">
      <strong>Calen</strong>
      <span>가계부 + 여행 기록 워크스페이스</span>
    </div>

    <div class="sidebar__user">
      <strong>{{ currentUser.displayName }}</strong>
      <span>{{ currentUser.loginId }}</span>
    </div>

    <div class="sidebar__groups">
      <section v-for="group in navGroups" :key="group.label" class="sidebar__group">
        <h2>{{ group.label }}</h2>
        <button
          v-for="item in group.items"
          :key="item.key"
          :class="['sidebar__link', { 'sidebar__link--active': activeRoute === item.key }]"
          @click="emit('navigate', item.key)"
        >
          <strong>{{ item.label }}</strong>
          <span>{{ item.description }}</span>
        </button>
      </section>
    </div>

    <button class="sidebar__logout" @click="emit('logout')">로그아웃</button>
  </aside>
</template>