<script setup>
import TravelHubWorkspace from './TravelHubWorkspace.vue'

defineProps({
  route: {
    type: String,
    default: 'travel-money',
  },
})
</script>

<template>
  <TravelHubWorkspace :route="route" />
</template>
