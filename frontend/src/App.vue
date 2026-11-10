<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import SheetDiffPage from './pages/SheetDiffPage.vue'
import SchedulePage from './pages/SchedulePage.vue'
import TeachersPage from './pages/TeachersPage.vue'
import ConfigPage from './pages/ConfigPage.vue'
import GuidePage from './pages/GuidePage.vue'
import FinSupportPage from './pages/FinSupportPage.vue'
import NotificationsPage from './pages/NotificationsPage.vue'

const route = ref(location.pathname)
const page = ref(null)
const mobileQuery = matchMedia('(max-width: 700px)')
const mobile = ref(mobileQuery.matches)
const drawerOpen = ref(false)
function updateMobile(event) { mobile.value = event.matches; drawerOpen.value = false }
let historyIndex = history.state?.compassIndex ?? 0
let restoringHistory = false
let leaving = false
history.replaceState({ ...history.state, compassIndex: historyIndex }, '', location.href)
const canAccessConfig = Boolean(window.__APP_CONFIG__?.canAccessConfig)
const canAccessFinSupport = Boolean(window.__APP_CONFIG__?.canAccessFinSupport)
const canAccessNotifications = Boolean(window.__APP_CONFIG__?.canAccessNotifications)
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
  ...(canAccessNotifications ? [{ path: '/notifications', label: 'Уведомления', icon: 'notifications', component: NotificationsPage }] : []),
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
  drawerOpen.value = false
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
  mobileQuery.addEventListener('change', updateMobile)
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
  mobileQuery.removeEventListener('change', updateMobile)
  quotaEvents?.close()
  clearTimeout(quotaResetTimer)
})
</script>

<template>

    <GuidePage v-if="route === '/guide'" />
    <m3e-drawer-container v-else class="app-layout app-drawer" :start="!mobile || drawerOpen" :start-mode="mobile ? 'over' : 'side'" @change.self="drawerOpen = $event.target.start">
      <div slot="start" class="nav-column">
        <m3e-nav-rail id="nav-rail" :mode="mobile ? 'expanded' : 'auto'">
          <m3e-icon-button v-if="mobile" aria-label="Закрыть навигацию" @click="drawerOpen = false"><m3e-icon name="close" /></m3e-icon-button>
          <m3e-icon-button v-else toggle aria-label="Развернуть навигацию">
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
      <main class="app-content mobile-content">
        <div v-if="mobile" class="mobile-navigation-bar"><m3e-icon-button aria-label="Открыть навигацию" @click="drawerOpen = true"><m3e-icon name="menu" /></m3e-icon-button><span>{{ activeRoute.label }}</span><div id="mobile-page-actions" class="mobile-page-actions" /></div>
        <component :is="activeRoute.component" :key="activeRoute.path" ref="page" />
      </main>
    </m3e-drawer-container>

</template>

<style scoped>
.app-drawer { display: block; width: 100%; height: 100dvh; --m3e-drawer-container-color: var(--md-sys-color-surface-container-low); }
.nav-column { width: max-content; }
.mobile-content { height: 100%; container-type: inline-size; }
.mobile-navigation-bar > span { flex: 1; min-width: 0; }
.mobile-page-actions { display: flex; align-items: center; gap: 4px; }
.mobile-navigation-bar { display: flex; align-items: center; gap: 12px; height: 48px; flex: none; padding-inline: 8px; background: var(--md-sys-color-surface-container-low); }
@media (min-width: 701px) { .app-drawer { --md-sys-motion-duration-medium-4: 0ms; } }
@media (max-width: 700px) { .mobile-content { display: flex; flex-direction: column; padding-top: 0; } .mobile-content > :last-child { flex: 1; min-height: 0; } }
</style>
