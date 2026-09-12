<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import VirtualList from '../components/VirtualList.vue'

const filters = [
  ['all', 'Все'], ['valid', 'Проверенные'], ['invalid', 'С ошибками'], ['deleted', 'Удалённые'],
]
const params = new URLSearchParams(location.search)
const filter = ref(filters.some(([value]) => value === params.get('f')) ? params.get('f') : 'all')
const search = ref('')
const state = ref({ files: [], hasUnreadyFiles: false, canOpenScheduleApproval: false, filter: filter.value })
const loading = ref(true)
const busy = ref(false)
const uploading = ref(false)
const error = ref('')
const deleteMode = ref(false)
const selected = ref(new Set())
const activeFileId = ref(null)
const fileInput = ref()
const approvalDialog = ref()
const downloadDialog = ref()
const approval = ref({ status: 'IDLE', progress: 0, message: '' })
const approvalPercent = computed(() => {
  const progress = Number(approval.value.progress ?? 0)
  return Number.isFinite(progress) ? Math.min(100, Math.max(0, progress)) : 0
})
const preview = ref({ groupsToRemove: [], duplicateGroups: [] })
let events
let approvalEvents
const showProcessing = ref(false)
let hideProcessingTimer
const validationProgress = computed(() => {
  const total = Number(state.value.validationProgress?.total ?? 0)
  const completed = Number(state.value.validationProgress?.completed ?? 0)
  if (!Number.isFinite(total) || total <= 0 || !Number.isFinite(completed) || completed < 0) {
    return { total: 0, completed: 0 }
  }
  return { total: Math.trunc(total), completed: Math.min(Math.trunc(completed), Math.trunc(total)) }
})
const validationPercent = computed(() => validationProgress.value.total > 0
  ? Math.round(validationProgress.value.completed * 100 / validationProgress.value.total)
  : 0)
const processing = computed(() => Boolean(
  validationProgress.value.completed < validationProgress.value.total ||
  uploading.value || state.value.googleWaitMessage || state.value.filesLoaded === false ||
  state.value.files.some(file => file.status === 'PROCESSING')
))
watch(processing, active => {
  clearTimeout(hideProcessingTimer)
  if (active) showProcessing.value = true
  else hideProcessingTimer = setTimeout(() => { showProcessing.value = false }, 1500)
}, { immediate: true })

const files = computed(() => {
  const query = search.value.trim().toLocaleLowerCase('ru')
  return query ? state.value.files.filter(file => file.name.toLocaleLowerCase('ru').includes(query)) : state.value.files
})
const selectableCount = computed(() => files.value.filter(file => file.status !== 'EMPTY').length)

async function load() {
  loading.value = true
  try {
    state.value = await api(`/api/schedule?f=${filter.value}`)
    error.value = state.value.error || ''
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}
function connectScheduleEvents() {
  events?.close()
  events = new EventSource(`/api/schedule/events?f=${filter.value}`)
  events.addEventListener('schedule', event => {
    state.value = JSON.parse(event.data)
    selected.value = new Set([...selected.value].filter(id => state.value.files.some(file => file.fileId === id)))
  })
}
function chooseFilter(value) {
  filter.value = value
  deleteMode.value = false
  selected.value = new Set()
}
function toggle(id) {
  const next = new Set(selected.value)
  next.has(id) ? next.delete(id) : next.add(id)
  selected.value = next
}
async function upload(event) {
  if (!event.target.files.length) return
  busy.value = true
  uploading.value = true
  const body = new FormData()
  for (const file of event.target.files) body.append('files', file)
  try {
    state.value = await api(`/api/schedule/upload?f=${filter.value}`, { method: 'POST', body })
    error.value = state.value.error || ''
  } catch (cause) {
    error.value = cause.message
  } finally {
    event.target.value = ''
    busy.value = false
    uploading.value = false
  }
}
async function removeSelected() {
  if (!selected.value.size) return
  busy.value = true
  try {
    state.value = await api(`/api/schedule/delete?f=${filter.value}`, {
      method: 'POST', body: JSON.stringify({ ids: [...selected.value] }),
    })
    deleteMode.value = false
    selected.value = new Set()
    error.value = state.value.error || ''
  } catch (cause) {
    error.value = cause.message
  } finally {
    busy.value = false
  }
}
async function restore(fileId) {
  busy.value = true
  try {
    state.value = await api(`/api/schedule/restore/${encodeURIComponent(fileId)}?f=${filter.value}`, { method: 'POST' })
  } catch (cause) {
    error.value = cause.message
  } finally {
    busy.value = false
  }
}
function openFile(file) {
  activeFileId.value = file.fileId
  if (deleteMode.value) toggle(file.fileId)
  else if (file.status !== 'EMPTY') window.open(`/schedule/editor/${encodeURIComponent(file.fileId)}`, '_blank', 'noopener')
}
function downloadFile(fileId) {
  location.assign(fileId ? `/schedule/download/${encodeURIComponent(fileId)}` : '/schedule/download')
}
function downloadAll() {
  if (state.value.hasUnreadyFiles) downloadDialog.value.showModal()
  else downloadFile()
}
async function openApproval() {
  error.value = ''
  preview.value = { groupsToRemove: [], duplicateGroups: [] }
  approvalDialog.value.showModal()
  try {
    preview.value = await api('/api/schedule/approve/preview')
  } catch (cause) {
    error.value = cause.message
  }
}
async function approveSchedule() {
  try {
    await api('/api/schedule/approve', { method: 'POST' })
  } catch (cause) {
    error.value = cause.message
  }
}
function statusColor(status) {
  return { VALID: '#2dc052', INVALID: '#ff3b30', PROCESSING: '#808b9f', EMPTY: '#b0a7a0' }[status]
}
function statusShape(status) {
  return { VALID: 'sunny', INVALID: 'triangle', PROCESSING: '9-sided-cookie', EMPTY: 'circle' }[status]
}
function statusIcon(status) {
  return { VALID: 'check', INVALID: 'exclamation', PROCESSING: 'sync', EMPTY: 'inventory_2' }[status]
}

watch(filter, async value => {
  const url = value === 'all' ? '/schedule' : `/schedule?f=${value}`
  history.replaceState({}, '', url)
  await load()
  connectScheduleEvents()
})
onMounted(async () => {
  await load()
  connectScheduleEvents()
  approvalEvents = new EventSource('/api/schedule/approve/events')
  approvalEvents.addEventListener('approval', event => {
    approval.value = JSON.parse(event.data)
    if (approval.value.status === 'SUCCESS') setTimeout(() => { approval.value = { status: 'IDLE', progress: 0 } }, 5000)
  })
})
onBeforeUnmount(() => { clearTimeout(hideProcessingTimer); events?.close(); approvalEvents?.close() })
</script>

<template>
  <div class="page-shell">
    <div class="schedule-header schedule-page-header">
      <m3e-search-bar id="file-search-bar" clearable @clear="search = ''">
        <m3e-icon name="search" slot="leading" />
        <input v-model="search" slot="input" placeholder="Поиск файлов..." />
      </m3e-search-bar>
      <div class="actions-wrapper">
        <m3e-button v-if="deleteMode" @click="deleteMode = false; selected = new Set()">
          <m3e-icon slot="icon" name="close" variant="rounded" />Отмена
        </m3e-button>
        <m3e-button v-else :disabled="busy" @click="fileInput.click()">
          <m3e-circular-progress-indicator v-if="uploading" class="upload-loader" slot="icon" indeterminate />
          <m3e-icon v-else slot="icon" name="upload" />Загрузить новый файл
        </m3e-button>
        <m3e-button @click="downloadAll"><m3e-icon slot="icon" name="download" />Скачать все</m3e-button>
        <m3e-button
          :disabled="filter === 'deleted' || busy || (deleteMode && !selected.size)"
          @click="deleteMode ? removeSelected() : deleteMode = true"
        >
          <m3e-icon slot="icon" name="delete" />
          {{ deleteMode ? `Удалить ${selected.size}/${selectableCount}` : 'Удалить файлы' }}
        </m3e-button>
        <input ref="fileInput" hidden type="file" accept=".xlsx,.xls" multiple @change="upload" />
      </div>
    </div>

    <m3e-content-pane class="schedule-pane">
      <div class="list-page-content">
        <div class="schedule-filter-row">
          <m3e-filter-chip-set aria-label="Фильтр по статусу" @change="$event.currentTarget.value && chooseFilter($event.currentTarget.value)">
            <m3e-filter-chip
              v-for="([value, label]) in filters" :key="value" :value="value" :selected="filter === value"
              @beforeinput.prevent @click="chooseFilter(value)"
            >{{ label }}</m3e-filter-chip>
          </m3e-filter-chip-set>
          <div class="approve-wrapper" :title="state.canOpenScheduleApproval ? '' : 'Есть непроверенные или обрабатывающиеся файлы'">
            <m3e-button variant="tonal" :disabled="!state.canOpenScheduleApproval" @click="openApproval">
              Подтвердить расписание
            </m3e-button>
          </div>
        </div>

        <div v-if="showProcessing" class="message wait-message" role="status">
          <span>Обработка файлов. Подождите</span>
          <template v-if="validationProgress.total > 0">
            <m3e-linear-progress-indicator :value="validationPercent" aria-label="Проверка файлов" />
            <small>Обработано {{ validationProgress.completed }} из {{ validationProgress.total }} · {{ validationPercent }}%</small>
          </template>
          <m3e-linear-progress-indicator v-else mode="query" aria-label="Обработка файлов" />
        </div>
        <div v-if="error" class="message error-message"><m3e-icon name="error" />{{ error }}</div>
        <div class="table-heading schedule-grid"><span /><b>Имя файла</b><b>Дата изменения</b><b>Дата создания</b><span /></div>
        <m3e-divider />
        <div v-if="loading" class="empty-state"><m3e-circular-progress-indicator indeterminate /></div>
        <VirtualList v-else-if="files.length" :items="files" :item-height="48" class="page-virtual-list">
          <template #default="{ item: file }">
            <m3e-list-action class="virtual-action" :selected="activeFileId === file.fileId" :aria-current="activeFileId === file.fileId ? 'true' : null" @click="openFile(file)">
              <div class="schedule-grid">
                <m3e-shape :name="statusShape(file.status)" :class="{ 'processing-shape': file.status === 'PROCESSING' }" :title="file.statusText" :style="{ '--m3e-shape-container-color': statusColor(file.status), '--m3e-shape-size': '30px' }">
                  <div class="status-icon"><m3e-icon :name="statusIcon(file.status)" variant="rounded" /></div>
                </m3e-shape>
                <span class="file-name">
                  <span class="file-name-content">{{ file.name }}<small v-if="file.supportingText">{{ file.supportingText }}</small></span>
                  <span v-if="file.hasChanges" class="file-change-dot" role="img" aria-label="Изменён после последнего подтверждения" title="Изменён после последнего подтверждения" />
                </span>
                <span>{{ file.modifiedTime }}</span><span>{{ file.createdTime }}</span>
                <m3e-checkbox v-if="deleteMode && file.status !== 'EMPTY'" :checked="selected.has(file.fileId)" @click.stop="toggle(file.fileId)" />
                <m3e-button v-else-if="file.status === 'EMPTY'" variant="text" @click.stop="restore(file.fileId)">Восстановить</m3e-button>
                <m3e-icon-button v-else aria-label="Скачать файл" @click.stop="downloadFile(file.fileId)"><m3e-icon name="download" /></m3e-icon-button>
              </div>
            </m3e-list-action>
            <m3e-divider></m3e-divider>
          </template>
        </VirtualList>
        <div v-else class="empty-state"><m3e-icon name="calendar_today" /><m3e-heading variant="headline" size="small">Файлы не найдены</m3e-heading></div>
      </div>
    </m3e-content-pane>

    <dialog ref="downloadDialog" class="app-dialog">
      <h2>Скачать все файлы?</h2>
      <p>В списке есть файлы с ошибками или файлы, которые ещё обрабатываются. Они тоже попадут в архив.</p>
      <div class="dialog-actions"><m3e-button @click="downloadDialog.close()">Отмена</m3e-button><m3e-button variant="filled" @click="downloadFile()">Скачать</m3e-button></div>
    </dialog>

    <dialog ref="approvalDialog" class="app-dialog">
      <h2>Подтвердить расписание?</h2>
      <template v-if="approval.status === 'RUNNING'">
        <m3e-linear-progress-indicator variant="wavy" :value="approvalPercent" />
        <p>Файлы отправляются: {{ approvalPercent }}%. Страницу можно закрыть.</p>
      </template>
      <template v-else>
        <p>На сервер будут отправлены только новые и изменённые файлы. Удалённые группы будут убраны из расписания.</p>
        <p v-if="preview.groupsToRemove.length" class="warning">Будут удалены группы: {{ preview.groupsToRemove.join(', ') }}</p>
        <p v-if="preview.duplicateGroups.length" class="warning">Дублируются группы: {{ preview.duplicateGroups.join(', ') }}</p>
      </template>
      <div class="dialog-actions">
        <m3e-button @click="approvalDialog.close()">Закрыть</m3e-button>
        <m3e-button v-if="approval.status !== 'RUNNING'" variant="filled" :disabled="preview.duplicateGroups.length > 0" @click="approveSchedule">Продолжить</m3e-button>
      </div>
    </dialog>

    <div v-if="approval.status !== 'IDLE'" class="snackbar" :class="approval.status.toLowerCase()">
      {{ approval.message || (approval.status === 'SUCCESS' ? 'Расписание отправлено' : 'Отправка расписания') }}
    </div>
  </div>
</template>
