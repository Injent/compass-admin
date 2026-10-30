<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import SheetDiffPage from './pages/SheetDiffPage.vue'
import SchedulePage from './pages/SchedulePage.vue'
import TeachersPage from './pages/TeachersPage.vue'
import ConfigPage from './pages/ConfigPage.vue'
import GuidePage from './pages/GuidePage.vue'
import FinSupportPage from './pages/FinSupportPage.vue'

const route = ref(location.pathname)
const page = ref(null)
let historyIndex = history.state?.compassIndex ?? 0
let restoringHistory = false
let leaving = false
history.replaceState({ ...history.state, compassIndex: historyIndex }, '', location.href)
const canAccessConfig = Boolean(window.__APP_CONFIG__?.canAccessConfig)
const canAccessFinSupport = Boolean(window.__APP_CONFIG__?.canAccessFinSupport)
const appVersion = window.__APP_CONFIG__?.appVersion
const sheetsQuota = ref(null)
let quotaEvents
let quotaResetTimer
const quotaTitle = computed(() => sheetsQuota.value
  ? `Google Sheets: осталось ${sheetsQuota.value.remainingPercent}% · использовано ${sheetsQuota.value.usedRequests} из 60 запросов`
  : 'Загрузка лимита Google Sheets')
const finSupportOnly = canAccessFinSupport && !canAccessConfig
const routes = [
  ...(!finSupportOnly ? [
  { path: '/schedule', label: 'Расписание', icon: 'calendar_today', component: SchedulePage },
  { path: '/sheetdiff', label: 'Сравнение', icon: 'difference', component: SheetDiffPage },
  { path: '/teachers', label: 'Преподаватели', icon: 'co_present', component: TeachersPage },
  ] : []),
  ...(canAccessFinSupport ? [{ path: '/finsupport', label: 'Материалка', icon: 'volunteer_activism', component: FinSupportPage }] : []),
  ...(canAccessConfig ? [{ path: '/config', label: 'Конфиг', icon: 'call_merge', component: ConfigPage }] : []),
]
const activeRoute = computed(() => routes.find(item => route.value.startsWith(item.path)) || routes[0])

async function canLeave() {
  if (leaving) return false
  leaving = true
  try { return await (page.value?.beforeLeave?.() ?? true) }
  finally { leaving = false }
}
async function navigate(path) {
  if (route.value === path && !location.search) return
  if (!await canLeave()) return
  history.pushState({ compassIndex: ++historyIndex }, '', path)
  route.value = path
}
async function onPopState(event) {
  if (restoringHistory) { restoringHistory = false; return }
  const targetIndex = event.state?.compassIndex ?? 0
  if (!await canLeave()) {
    restoringHistory = true
    history.go(historyIndex - targetIndex)
    return
  }
  historyIndex = targetIndex
  route.value = location.pathname
}
async function logout(event) {
  if (await canLeave()) event.target.submit()
}
onMounted(() => {
  if (location.pathname === '/') navigate(routes[0].path)
  addEventListener('popstate', onPopState)
  if (canAccessConfig && route.value !== '/guide') {
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

    <GuidePage v-if="route === '/guide'" />
    <div v-else class="app-layout">
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
        <span v-if="canAccessConfig && appVersion" class="app-version">v{{ appVersion }}</span>
        <div v-if="canAccessConfig" class="sheets-quota" :title="quotaTitle">
          <m3e-circular-progress-indicator
            :value="sheetsQuota?.remainingPercent ?? 0" :indeterminate="!sheetsQuota"
            :aria-label="quotaTitle"
          >{{ sheetsQuota ? `${sheetsQuota.remainingPercent}` : '' }}</m3e-circular-progress-indicator>
        </div>
        <form class="logout-form" method="post" action="/auth/logout" @submit.prevent="logout">
          <m3e-icon-button type="submit" aria-label="Выход"><m3e-icon name="logout" /></m3e-icon-button>
        </form>
      </div>
      <main class="app-content">
        <component :is="activeRoute.component" :key="activeRoute.path" ref="page" />
      </main>
    </div>

</template>
