<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import SchedulePage from './pages/SchedulePage.vue'
import TeachersPage from './pages/TeachersPage.vue'
import ConfigPage from './pages/ConfigPage.vue'

const route = ref(location.pathname)
const canAccessConfig = Boolean(window.__APP_CONFIG__?.canAccessConfig)
const sheetsQuota = ref(null)
let quotaEvents
let quotaResetTimer
const quotaTitle = computed(() => sheetsQuota.value
  ? `Google Sheets: осталось ${sheetsQuota.value.remainingPercent}% · использовано ${sheetsQuota.value.usedRequests} из 60 запросов`
  : 'Загрузка лимита Google Sheets')
const routes = [
  { path: '/schedule', label: 'Расписание', icon: 'calendar_today', component: SchedulePage },
  { path: '/teachers', label: 'Преподаватели', icon: 'co_present', component: TeachersPage },
  ...(canAccessConfig ? [{ path: '/config', label: 'Конфиг', icon: 'call_merge', component: ConfigPage }] : []),
]
const activeRoute = computed(() => routes.find(item => route.value.startsWith(item.path)) || routes[0])

function navigate(path) {
  if (location.pathname !== path || location.search) history.pushState({}, '', path)
  route.value = path
}
function onPopState() { route.value = location.pathname }
onMounted(() => {
  if (location.pathname === '/') navigate('/schedule')
  addEventListener('popstate', onPopState)
  if (canAccessConfig) {
    quotaEvents = new EventSource('/api/google-sheets/quota/events')
    quotaEvents.addEventListener('quota', event => {
      clearTimeout(quotaResetTimer)
      sheetsQuota.value = JSON.parse(event.data)
      const resetAfter = sheetsQuota.value.resetAfterMillis
      if (resetAfter > 0) {
        quotaResetTimer = setTimeout(() => {
          sheetsQuota.value = { remainingPercent: 100, usedRequests: 0, resetAfterMillis: 0 }
        }, resetAfter)
      }
    })
  }
})
onBeforeUnmount(() => {
  removeEventListener('popstate', onPopState)
  quotaEvents?.close()
  clearTimeout(quotaResetTimer)
})
</script>

<template>

    <div class="app-layout">
      <div class="nav-column">
        <m3e-nav-rail id="nav-rail">
          <m3e-icon-button toggle aria-label="Развернуть навигацию">
            <m3e-icon name="menu" variant="rounded" />
            <m3e-icon slot="selected" name="menu_open" />
            <m3e-nav-rail-toggle for="nav-rail" />
          </m3e-icon-button>
          <m3e-nav-item
            v-for="item in routes"
            :key="item.path"
            :active="activeRoute.path === item.path"
            :selected="activeRoute.path === item.path"
            @click="navigate(item.path)"
          >
            <m3e-icon slot="icon" :name="item.icon" variant="rounded" />
            <m3e-icon slot="selected" :name="item.icon" variant="rounded" />
            <span class="nav-item-label">{{ item.label }}</span>
          </m3e-nav-item>
        </m3e-nav-rail>
        <div class="nav-column-spacer" />
        <div v-if="canAccessConfig" class="sheets-quota" :title="quotaTitle">
          <m3e-circular-progress-indicator
            :value="sheetsQuota?.remainingPercent ?? 0" :indeterminate="!sheetsQuota"
            :aria-label="quotaTitle"
          >{{ sheetsQuota ? `${sheetsQuota.remainingPercent}%` : '' }}</m3e-circular-progress-indicator>
        </div>
        <form class="logout-form" method="post" action="/auth/logout">
          <m3e-icon-button type="submit" aria-label="Выход"><m3e-icon name="logout" /></m3e-icon-button>
        </form>
      </div>
      <main class="app-content">
        <component :is="activeRoute.component" :key="activeRoute.path" />
      </main>
    </div>

</template>
