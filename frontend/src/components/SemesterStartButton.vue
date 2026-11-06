<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import '@m3e/web/date-input'
import { api } from '../api'
import { academicWeek, previewWeeks, shortAcademicDate, weekParity } from '../academicWeeks'

const dialog = ref()
const dateInput = ref()
const termStartDate = ref(null)
const draft = ref(null)
const inputValid = ref(false)
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')
const saveError = ref('')
const today = ref(moscowToday())
let todayTimer
const valid = computed(() => inputValid.value && draft.value instanceof Date && Number.isFinite(draft.value.getTime()))
const dateLabel = computed(() => termStartDate.value ? shortAcademicDate(termStartDate.value) : '')
const todayParity = computed(() => termStartDate.value && !loadError.value ? weekParity(academicWeek(today.value, termStartDate.value)) : null)
const weeks = computed(() => valid.value ? previewWeeks(draft.value, today.value) : [])

function moscowToday() {
  const parts = new Intl.DateTimeFormat('en', { timeZone: 'Europe/Moscow', year: 'numeric', month: 'numeric', day: 'numeric' }).formatToParts(new Date())
  const value = type => Number(parts.find(part => part.type === type).value)
  return new Date(value('year'), value('month') - 1, value('day'))
}

function parseDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(year, month - 1, day)
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? date : null
}
function isoDate(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const config = await api('/api/config')
    const date = parseDate(config.termStartDate)
    if (!date) throw new Error('В конфиге указана некорректная дата начала семестра')
    termStartDate.value = date
    return true
  } catch (cause) { loadError.value = cause.message; return false }
  finally { loading.value = false }
}
async function open() {
  if (loading.value || saving.value) return
  if (loadError.value && !await load()) return
  draft.value = new Date(termStartDate.value)
  inputValid.value = true
  saveError.value = ''
  dialog.value.showModal()
}
function updateDraft(event) {
  draft.value = event.currentTarget.value
  inputValid.value = event.currentTarget.checkValidity()
}
async function save() {
  if (!valid.value || saving.value || !dateInput.value.checkValidity()) return
  const value = isoDate(draft.value)
  saving.value = true
  saveError.value = ''
  try {
    const config = await api('/api/config')
    const saved = await api('/api/config', { method: 'PUT', body: JSON.stringify({ ...config, termStartDate: value }) })
    termStartDate.value = parseDate(saved.termStartDate)
    dialog.value.close()
  } catch (cause) { saveError.value = cause.message }
  finally { saving.value = false }
}
onMounted(() => {
  load()
  todayTimer = setInterval(() => { today.value = moscowToday() }, 60000)
})
onBeforeUnmount(() => clearInterval(todayTimer))
</script>

<template>
  <m3e-chip v-if="todayParity" variant="outlined" class="today-parity">Сегодня <strong>{{ todayParity === 'чет.' ? 'ЧЕТНАЯ (2)' : 'НЕЧЕТНАЯ (1)' }}</strong> неделя</m3e-chip>
  <m3e-button variant="text" :disabled="loading || saving" :title="loadError || undefined" @click="open">
    <m3e-circular-progress-indicator v-if="loading" slot="icon" class="term-loader" indeterminate aria-label="Загрузка даты начала семестра" />
    <m3e-icon v-else-if="loadError" slot="icon" name="error" />
    Начало семестра: {{ loading ? 'загрузка…' : loadError ? 'ошибка загрузки' : dateLabel }}
    <m3e-icon slot="trailing-icon" name="edit" />
  </m3e-button>
  <dialog ref="dialog" class="app-dialog term-dialog" aria-labelledby="term-dialog-title" @cancel="saving && $event.preventDefault()">
    <h2 id="term-dialog-title">Начало семестра</h2>
    <m3e-form-field variant="outlined">
      <label slot="label" for="schedule-term-date">Дата начала семестра</label>
      <m3e-date-input id="schedule-term-date" ref="dateInput" :value.prop="draft" :disabled="saving" required day-label="День" month-label="Месяц" year-label="Год" @input="updateDraft" @change="updateDraft" />
      <m3e-icon-button slot="suffix" aria-label="Открыть календарь" :disabled="saving">
        <m3e-icon name="calendar_today" />
        <m3e-datepicker-toggle for="schedule-term-picker" />
      </m3e-icon-button>
    </m3e-form-field>
    <m3e-datepicker id="schedule-term-picker" for="schedule-term-date" label="Дата начала семестра" confirm-label="Выбрать" dismiss-label="Отмена" />
    <div v-if="weeks.length" class="week-preview" tabindex="0" role="region" aria-label="Предпросмотр 16 учебных недель">
      <table>
        <caption>Учебные недели</caption>
        <thead><tr><th scope="col">№</th><th scope="col">Начало</th><th scope="col">Конец</th><th scope="col">Чётность</th></tr></thead>
        <tbody>
          <tr v-for="week in weeks" :key="week.number" :class="{ 'current-week': week.current }" :aria-current="week.current ? 'date' : undefined">
            <th scope="row">{{ week.number }}</th>
            <td>{{ shortAcademicDate(week.from) }}</td>
            <td>{{ shortAcademicDate(week.to) }}</td>
            <td>{{ week.parity }}<span v-if="week.current" class="current-marker" aria-label="Текущая неделя"> ●</span></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="saveError" class="message error-message" role="alert">{{ saveError }}</div>
    <div class="dialog-actions">
      <m3e-button variant="text" :disabled="saving" @click="dialog.close()">Отмена</m3e-button>
      <m3e-button variant="filled" :disabled="!valid || saving" @click="save">
        <m3e-circular-progress-indicator v-if="saving" slot="icon" class="term-loader" indeterminate />
        Сохранить
      </m3e-button>
    </div>
  </dialog>
</template>

<style scoped>
.term-loader { --m3e-circular-flat-progress-indicator-diameter: 18px; }
.term-dialog m3e-form-field { width: 100%; }
.term-dialog .error-message { margin-top: 16px; }
.today-parity { --m3e-chip-container-shape: 999px; --m3e-chip-label-text-color: var(--md-sys-color-on-surface-variant); white-space: nowrap; }
.today-parity strong { font-weight: 700; }
.term-dialog { max-height: calc(100dvh - 32px); overflow: auto; }
.week-preview { max-height: 300px; overflow: auto; margin-top: 16px; border-radius: 12px; border: 1px solid var(--md-sys-color-outline-variant); }
table { width: 100%; border-collapse: collapse; font-size: 12px; text-align: left; }
caption { padding: 8px; font-weight: 500; text-align: left; background: var(--md-sys-color-surface-container); }
thead { position: sticky; top: 0; background: var(--md-sys-color-surface-container-high); }
th, td { padding: 6px 8px; border-bottom: 1px solid var(--md-sys-color-outline-variant); }
th { font-weight: 500; }
tbody tr:nth-child(odd) { background: var(--md-sys-color-surface-container); }
tbody tr.current-week { background: var(--md-sys-color-primary-container); color: var(--md-sys-color-on-primary-container); font-weight: 600; }
.current-marker { color: var(--md-sys-color-primary); }
@media (max-width: 520px) { th, td { padding: 6px 4px; } }
</style>
