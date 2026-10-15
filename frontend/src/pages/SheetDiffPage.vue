<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import VirtualList from '../components/VirtualList.vue'

const files = ref([])
const selectedId = ref(null)
const selected = computed(() => files.value.find(file => file.fileId === selectedId.value))
const upload = ref(null)
const fileInput = ref()
const loading = ref(true)
const busy = ref(false)
const complete = ref(false)
const dragging = ref(false)
const error = ref('')
let events

function update(state) {
  files.value = state.files
  if (!files.value.some(file => file.fileId === selectedId.value)) selectedId.value = null
  loading.value = state.filesLoaded === false
}
function chooseFile(list) {
  if (busy.value) return
  dragging.value = false
  if (!list?.length) return
  error.value = ''
  if (list.length !== 1 || !/\.(xlsx|xls)$/i.test(list[0].name) || list[0].size > 50 * 1024 * 1024 || list[0].size === 0) {
    upload.value = null
    error.value = 'Выберите один файл XLSX или XLS размером до 50 МБ'
    return
  }
  upload.value = list[0]
}
function back() {
  complete.value = false
  upload.value = null
  error.value = ''
}
async function compare() {
  if (!selected.value || !upload.value || busy.value) return
  busy.value = true
  error.value = ''
  const name = selected.value.name
  const body = new FormData()
  body.append('file', upload.value)
  try {
    const response = await fetch(`/api/sheetdiff/${encodeURIComponent(selectedId.value)}`, { method: 'POST', body, credentials: 'same-origin' })
    if (response.status === 401) {
      location.assign(`/auth?next=${encodeURIComponent(location.pathname)}`)
      throw new Error('Требуется авторизация')
    }
    if (!response.ok) {
      const result = await response.json().catch(() => ({}))
      throw new Error(result.error || 'Не удалось сравнить файлы')
    }
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `Сравнение ${name.replace(/\.(xlsx|xls)$/i, '')}.xlsx`
    document.body.append(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    upload.value = null
    complete.value = true
  } catch (cause) {
    error.value = cause.message
  } finally {
    busy.value = false
  }
}
onMounted(async () => {
  try {
    update(await api('/api/schedule?f=all'))
    events = new EventSource('/api/schedule/events?f=all')
    events.addEventListener('schedule', event => update(JSON.parse(event.data)))
  } catch (cause) {
    error.value = cause.message
    loading.value = false
  }
})
onBeforeUnmount(() => events?.close())
</script>

<template>
  <div class="page-shell">
    <div class="schedule-header sheetdiff-header">
      <m3e-icon-button v-if="complete" aria-label="Назад к списку файлов" @click="back">
        <m3e-icon name="arrow_back" variant="rounded" />
      </m3e-icon-button>
      <h1>Сравнение расписания</h1>
    </div>
    <m3e-content-pane class="schedule-pane">
      <div class="list-page-content sheetdiff-content">
        <div v-if="error" class="message error-message" role="alert">{{ error }}</div>
        <div v-if="complete" class="empty-state" role="status">
          <m3e-icon name="download_done" variant="rounded" />
          <h2>Файл сравнения готов</h2>
          <p>Скачивание началось. На листе «Старое» изменения выделены красно-розовым, на листе «Новое» — лаймовым.</p>
        </div>
        <template v-else>
          <p>Выберите активное расписание и новый файл. Будут сравнены значения ячеек первых листов.</p>
          <div class="sheetdiff-panels">
            <section class="sheetdiff-panel" aria-label="Старое расписание">
              <h2>Старое</h2>
              <div v-if="loading" class="empty-state"><m3e-circular-progress-indicator indeterminate aria-label="Загрузка файлов" /></div>
              <div v-else-if="!files.length" class="empty-state">Нет активных файлов расписания</div>
              <VirtualList v-else :items="files" :item-height="56" class="page-virtual-list" role="list" aria-label="Активные файлы">
                <template #default="{ item }">
                  <m3e-list-action class="virtual-action" :selected.attr="selectedId === item.fileId ? '' : null" :aria-current="selectedId === item.fileId ? 'true' : null" :disabled="busy" @click="selectedId = item.fileId">
                    <m3e-icon slot="leading" :name="selectedId === item.fileId ? 'radio_button_checked' : 'radio_button_unchecked'" variant="rounded" />
                    <span class="file-name">{{ item.name }}</span>
                  </m3e-list-action>
                </template>
              </VirtualList>
            </section>
            <section class="sheetdiff-panel" aria-label="Новое расписание">
              <h2>Новое</h2>
              <div class="sheetdiff-drop" :class="{ dragging }" @dragover.prevent="!busy && (dragging = true)" @dragleave.prevent="dragging = false" @drop.prevent="chooseFile($event.dataTransfer.files)">
                <m3e-icon :name="upload ? 'description' : 'upload_file'" variant="rounded" />
                <strong>{{ upload ? upload.name : 'Перетащите файл сюда' }}</strong>
                <span>XLSX или XLS, до 50 МБ</span>
                <m3e-button variant="tonal" :disabled="busy" @click="fileInput.click()">{{ upload ? 'Заменить файл' : 'Выбрать файл' }}</m3e-button>
                <input ref="fileInput" type="file" hidden accept=".xlsx,.xls" @change="chooseFile($event.target.files); $event.target.value = ''" />
              </div>
            </section>
          </div>
          <div class="sheetdiff-actions">
            <span v-if="selected">{{ selected.name }}</span>
            <m3e-button variant="filled" :disabled="!selected || !upload || busy" @click="compare">
              <m3e-circular-progress-indicator v-if="busy" slot="icon" class="upload-loader" indeterminate />
              <m3e-icon v-else slot="icon" name="difference" variant="rounded" />
              {{ busy ? 'Сравнение…' : 'Сравнить' }}
            </m3e-button>
          </div>
        </template>
      </div>
    </m3e-content-pane>
  </div>
</template>

<style scoped>
.sheetdiff-header { justify-content: flex-start; padding-left: 16px; }
h1 { font-size: 22px; margin: 0; }
h2 { font-size: 20px; margin: 0 0 16px; }
.sheetdiff-content > p { color: var(--md-sys-color-on-surface-variant); }
.sheetdiff-panels { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; flex: 1; min-height: 240px; }
.sheetdiff-panel { display: flex; flex-direction: column; min-width: 0; min-height: 0; padding: 20px; border: 1px solid var(--md-sys-color-outline-variant); border-radius: 20px; }
.sheetdiff-drop { display: flex; flex: 1; flex-direction: column; align-items: center; justify-content: center; gap: 20px; padding: 24px; border: 2px dashed var(--md-sys-color-outline); border-radius: 16px; text-align: center; overflow-wrap: anywhere; }
.sheetdiff-drop > m3e-icon { font-size: 48px; color: var(--md-sys-color-primary); }
.sheetdiff-drop.dragging { background: var(--md-sys-color-secondary-container); border-color: var(--md-sys-color-primary); }
.sheetdiff-actions { display: flex; justify-content: flex-end; align-items: center; gap: 16px; padding-top: 20px; }
.sheetdiff-actions > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty-state { text-align: center; }
@media (max-width: 760px) {
  .sheetdiff-content { overflow: auto; }
  .sheetdiff-panels { grid-template-columns: 1fr; flex: none; }
  .sheetdiff-panel { min-height: 260px; }
}
</style>
