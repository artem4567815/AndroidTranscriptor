<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { RouterView } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'

const settings = useSettingsStore()

function applyTheme(theme: 'light' | 'dark' | 'system') {
  const root = document.documentElement
  root.dataset.theme = theme
  root.style.colorScheme = theme === 'system' ? 'light dark' : theme
}

onMounted(() => applyTheme(settings.theme.value))
watch(() => settings.theme.value, applyTheme)
</script>

<template>
  <div class="app-shell">
    <RouterView v-slot="{ Component, route }">
      <Transition :name="route.meta.transition as string || 'slide-forward'" mode="out-in">
        <component :is="Component" :key="route.fullPath" />
      </Transition>
    </RouterView>
  </div>
</template>
