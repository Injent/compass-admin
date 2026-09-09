<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'

const config = ref(null)
const initial = ref('')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const saved = ref(false)
const rangeInput = ref('')
const supportEnabled = ref({ vkLinkSupport: false, maxLinkSupport: false, telegramLinkSupport: false })
const rangePattern = /^(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])\.\.(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/

const changed = computed(() => config.value && JSON.stringify(config.value) !== initial.value)
const valid = computed(() => {
  if (!config.value || !/^\d{4}-\d{2}-\d{2}$/.test(config.value.termStartDate)) return false
  if (!Number.isInteger(Number(config.value.versionCode)) || Number(config.value.versionCode) < 0) return false
  if (!isHttpUrl(config.value.downloadUrl)) return false
  if (!config.value.teacherSearchWarningDateRanges.length) return false
  return Object.keys(supportEnabled.value).every(name => !supportEnabled.value[name] || isHttpUrl(config.value[name]))
})

function isHttpUrl(value) {
  try { return ['http:', 'https:'].includes(new URL(value).protocol) } catch { return false }
}
async function load() {
  try {
    config.value = await api('/api/config')
    for (const name of Object.keys(supportEnabled.value)) supportEnabled.value[name] = Boolean(config.value[name])
    initial.value = JSON.stringify(config.value)
  } catch (cause) { error.value = cause.message }
  finally { loading.value = false }
}
function addRange() {
  const value = rangeInput.value.trim()
  if (!rangePattern.test(value)) return
  const parts = value.split('..')
  if (!config.value.teacherSearchWarningDateRanges.some(range => range.join('..') === value)) {
    config.value.teacherSearchWarningDateRanges.push(parts)
  }
  rangeInput.value = ''
}
function removeRange(index) { config.value.teacherSearchWarningDateRanges.splice(index, 1) }
function toggleSupport(name) {
  supportEnabled.value[name] = !supportEnabled.value[name]
  if (!supportEnabled.value[name]) config.value[name] = null
  else if (config.value[name] == null) config.value[name] = ''
}
async function save() {
  if (!changed.value || !valid.value) return
  busy.value = true
  error.value = ''
  saved.value = false
  try {
    config.value.versionCode = Number(config.value.versionCode)
    config.value = await api('/api/config', { method: 'PUT', body: JSON.stringify(config.value) })
    initial.value = JSON.stringify(config.value)
    saved.value = true
    setTimeout(() => { saved.value = false }, 3000)
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}
function reset() {
  config.value = JSON.parse(initial.value)
  for (const name of Object.keys(supportEnabled.value)) supportEnabled.value[name] = Boolean(config.value[name])
}

onMounted(load)
</script>

<template>
  <div class="page-shell">
    <div class="schedule-header">
      <div />
      <div class="actions-wrapper">
        <m3e-button variant="outlined" :disabled="!changed || busy" @click="reset"><m3e-icon slot="icon" name="restart_alt" />Вернуть к исходному</m3e-button>
        <m3e-button variant="filled" :disabled="!changed || !valid || busy" @click="save"><m3e-icon slot="icon" name="save" />Сохранить</m3e-button>
      </div>
    </div>
    <m3e-content-pane class="schedule-pane">
      <div class="config-content">
        <div v-if="error" class="message error-message"><m3e-icon name="error" />{{ error }}</div>
        <div v-if="saved" class="message success-message"><m3e-icon name="check_circle" />Настройки сохранены</div>
        <div v-if="loading" class="empty-state"><m3e-circular-progress-indicator indeterminate /></div>
        <form v-else-if="config" class="config-form" @submit.prevent="save">
          <m3e-heading variant="title" size="medium">Расписание</m3e-heading>
          <m3e-form-field variant="outlined">
            <label slot="label" for="term-start-date">Дата начала семестра</label>
            <input id="term-start-date" v-model="config.termStartDate" type="date" required />
          </m3e-form-field>
          <m3e-form-field variant="outlined">
            <label slot="label" for="warning-range">Периоды предупреждений</label>
            <m3e-input-chip-set>
              <m3e-input-chip
                v-for="(range, index) in config.teacherSearchWarningDateRanges" :key="range.join('..')"
                removable @click="removeRange(index)"
              >{{ range.join('..') }}</m3e-input-chip>
              <input id="warning-range" v-model="rangeInput" slot="input" placeholder="MM-DD..MM-DD" @keydown.enter.prevent="addRange" @blur="addRange" />
            </m3e-input-chip-set>
            <span slot="hint">Например: 12-08..02-07</span>
          </m3e-form-field>

          <m3e-heading variant="title" size="medium">Публикация приложения</m3e-heading>
          <m3e-form-field variant="outlined"><label slot="label" for="version-code">Код версии</label><input id="version-code" v-model="config.versionCode" type="number" min="0" step="1" required /></m3e-form-field>
          <m3e-form-field variant="outlined"><label slot="label" for="download-url">Ссылка загрузки</label><input id="download-url" v-model="config.downloadUrl" type="url" required /></m3e-form-field>

          <m3e-heading variant="title" size="medium">Поддержка</m3e-heading>
          <div v-for="(label, name) in { vkLinkSupport: 'VK', maxLinkSupport: 'MAX', telegramLinkSupport: 'Telegram' }" :key="name" class="support-row">
            <m3e-form-field variant="outlined">
              <label slot="label" :for="name">{{ label }}</label>
              <input :id="name" v-model="config[name]" type="url" :disabled="!supportEnabled[name]" />
            </m3e-form-field>
            <m3e-switch icons="both" :checked="supportEnabled[name]" @click="toggleSupport(name)" />
          </div>
        </form>
      </div>
    </m3e-content-pane>
  </div>
</template>
