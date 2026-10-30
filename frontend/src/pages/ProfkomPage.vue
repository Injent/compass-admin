<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import ProfkomScheduleForm from '../components/ProfkomScheduleForm.vue'

const settings = ref(null)
const initial = ref('')
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const saved = ref(false)
const month = ref('')
const overrideActive = ref(false)
const draft = ref({ enabled: true, dayRange: { from: 1, to: 10 }, hours: { days: {} } })
const schedule = computed(() => settings.value?.schedules.materialAid)
const editedSettings = computed(() => {
  if (!settings.value) return null
  const overrides = { ...schedule.value.overrides }
  if (overrideActive.value) {
    const override = {}
    if (overrideActive.value) override.enabled = draft.value.enabled
    if (overrideActive.value) override.dayRange = { ...draft.value.dayRange }
    if (overrideActive.value) override.hours = { ...draft.value.hours }
    if (overrides[month.value]?.createdAt) override.createdAt = overrides[month.value].createdAt
    overrides[month.value] = override
  }
  return { schedules: { materialAid: { ...schedule.value, overrides } } }
})
const changed = computed(() => editedSettings.value && JSON.stringify(editedSettings.value) !== initial.value)
const monthStart = computed(() => new Date(`${month.value}-01T00:00:00`))
const monthEnd = computed(() => new Date(monthStart.value.getFullYear(), monthStart.value.getMonth() + 1, 0))
const monthLabel = computed(() => historyMonth(month.value))
const history = computed(() => Object.entries(schedule.value?.overrides || {}).sort(([a], [b]) => b.localeCompare(a)))
const defaultDayErrors = computed(() => {
  const range = schedule.value?.default.dayRange
  if (!range) return { from: '', to: '' }
  return {
    from: dayError(range.from),
    to: dayError(range.to) || (!dayError(range.from) && range.to < range.from ? 'Окончание не может быть раньше начала' : ''),
  }
})
const overrideDayErrors = computed(() => {
  const range = draft.value.dayRange
  const validate = value => {
    if (typeof value === 'string') return parseDate(value) ? 'Выберите дату текущего месяца' : 'Введите существующую дату: ДД.ММ.ГГГГ'
    return dayError(value, monthEnd.value.getDate())
  }
  return {
    from: validate(range.from),
    to: validate(range.to) || (!validate(range.from) && range.to < range.from ? 'Окончание не может быть раньше начала' : ''),
  }
})
const defaultsValid = computed(() => schedule.value && !Object.values(defaultDayErrors.value).some(Boolean) && validHours(schedule.value.default.hours))
const overrideValid = computed(() => !overrideActive.value || (validHours(draft.value.hours) && !Object.values(overrideDayErrors.value).some(Boolean)))

function currentMonth() {
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Moscow', year: 'numeric', month: '2-digit' }).formatToParts(new Date())
  return `${parts.find(part => part.type === 'year').value}-${parts.find(part => part.type === 'month').value}`
}
function dayError(day, maxDay = 31) {
  return Number.isInteger(day) && day >= 1 && day <= maxDay ? '' : `Введите целое число от 1 до ${maxDay}`
}
function validHours(hours) {
  const pattern = /^([01]\d|2[0-3]):[0-5]\d$/
  return Object.values(hours.days).every(day => !day.enabled || (pattern.test(day.from) && pattern.test(day.to) && day.from < day.to))
}
function parseDate(value) {
  if (!/^\d{2}\.\d{2}\.\d{4}$/.test(value)) return null
  const [day, month, year] = value.split('.').map(Number)
  const date = new Date(year, month - 1, day)
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? date : null
}
function copy(value) { return JSON.parse(JSON.stringify(value)) }
function resetDraft() {
  overrideActive.value = false
  draft.value = copy(schedule.value.default)
}
function toggleOverride(checked) {
  resetDraft()
  overrideActive.value = checked
}
function toggleMonth(value, checked) {
  schedule.value.default.months = checked
    ? [...schedule.value.default.months, value].sort((a, b) => a - b)
    : schedule.value.default.months.filter(month => month !== value)
}
const months = Array.from({ length: 12 }, (_, index) => new Date(2026, index, 1).toLocaleDateString('ru-RU', { month: 'long' }))
function describe(override) {
  return [
    override.enabled == null ? null : override.enabled ? 'Приём открыт' : 'Приём закрыт',
    override.dayRange ? `${override.dayRange.from}–${override.dayRange.to} числа` : null,
    override.hours ? Object.entries(override.hours.days).filter(([, day]) => day.enabled).map(([key, day]) => `${{ monday: 'Пн', tuesday: 'Вт', wednesday: 'Ср', thursday: 'Чт', friday: 'Пт', saturday: 'Сб', sunday: 'Вс' }[key]} ${day.from}–${day.to}`).join(', ') || 'Нет рабочих дней' : null,
  ].filter(Boolean).join(' · ')
}
function historyMonth(value) {
  const date = new Date(`${value}-01T00:00:00`)
  return `${date.toLocaleDateString('ru-RU', { month: 'long' })} ${date.getFullYear()}`
}
function removeOverride(key) {
  delete schedule.value.overrides[key]
  if (key === month.value) resetDraft()
  saved.value = false
}
function reset() { settings.value = JSON.parse(initial.value); saved.value = false; resetDraft() }
async function save() {
  if (!changed.value) return true
  if (!defaultsValid.value || !overrideValid.value || busy.value) {
    error.value = 'Исправьте ошибки в полях перед сохранением.'
    return false
  }
  if (month.value !== currentMonth()) {
    error.value = 'Месяц изменился. Обновите страницу перед сохранением.'
    return false
  }
  busy.value = true
  error.value = ''
  saved.value = false
  try {
    settings.value = await api('/api/profkom', { method: 'PUT', body: JSON.stringify(editedSettings.value) })
    initial.value = JSON.stringify(settings.value)
    resetDraft()
    saved.value = true
    return true
  } catch (cause) { error.value = cause.message; return false }
  finally { busy.value = false }
}
async function beforeLeave() {
  if (busy.value) return false
  if (!changed.value) return true
  if (window.confirm('Сохранить изменения перед выходом?\nОК — сохранить, Отмена — выйти без сохранения.')) return save()
  reset()
  return true
}
function beforeUnload(event) {
  if (!changed.value) return
  event.preventDefault()
  event.returnValue = ''
}
defineExpose({ beforeLeave })
onBeforeUnmount(() => removeEventListener('beforeunload', beforeUnload))
onMounted(async () => {
  addEventListener('beforeunload', beforeUnload)
  month.value = currentMonth()
  try {
    settings.value = await api('/api/profkom')
    initial.value = JSON.stringify(settings.value)
    resetDraft()
  } catch (cause) { error.value = cause.message }
  finally { loading.value = false }
})
</script>

<template>
  <div id="profkom-scroll" class="profkom-page">
    <m3e-app-bar class="profkom-header" for="profkom-scroll">
      <h1 slot="title">Профком</h1>
      <div slot="trailing" class="actions-wrapper">
        <m3e-button variant="text" :disabled="!changed || busy" @click="reset">Сбросить</m3e-button>
        <m3e-button variant="filled" :disabled="!changed || !defaultsValid || !overrideValid || busy" @click="save"><m3e-icon slot="icon" name="save" />{{ busy ? 'Сохранение…' : 'Сохранить' }}</m3e-button>
      </div>
    </m3e-app-bar>
    <div v-if="error" class="message error-message" role="alert"><m3e-icon name="error" />{{ error }}</div>
    <div v-if="saved && !changed" class="message success-message" role="status"><m3e-icon name="check_circle" />Настройки сохранены</div>
    <div v-if="loading" class="empty-state"><m3e-circular-progress-indicator indeterminate aria-label="Загрузка настроек" /></div>
    <template v-else-if="schedule">
      <div class="profkom-cards">
        <section>
          <h2 class="form-title"><m3e-icon name="event_repeat" />Постоянный график</h2>
          <div class="profkom-fields">

            <ProfkomScheduleForm :value="schedule.default" :disabled="busy" prefix="default" split-cards :day-errors="defaultDayErrors" />
            <section class="reception-months">
              <h3>Месяцы приёма</h3>
              <m3e-card class="profkom-card months-card">
              <div slot="content">
              <label v-for="(label, index) in months" :key="index" class="profkom-toggle profkom-month">
                <span>{{ label }}</span><m3e-checkbox :checked="schedule.default.months.includes(index + 1)" :disabled="busy" :aria-label="label" @change="toggleMonth(index + 1, $event.target.checked)" />
              </label>
              </div>
              </m3e-card>
            </section>
          </div>
        </section>

        <section>
          <h2 class="form-title"><m3e-icon name="edit_calendar" />Исключение на {{ monthLabel }}</h2>
        <m3e-card variant="filled" class="profkom-card">
          <div slot="content" class="profkom-fields">
            <m3e-button v-if="!overrideActive" variant="filled" :disabled="busy" @click="toggleOverride(true)">Добавить исключение</m3e-button>
            <ProfkomScheduleForm v-if="overrideActive" :value="draft" :disabled="busy" prefix="override" :day-errors="overrideDayErrors" :max-day="monthEnd.getDate()" />
            <m3e-button v-if="overrideActive" variant="outlined" :disabled="busy" @click="resetDraft">Отменить добавление исключения</m3e-button>
          </div>
          <div slot="footer" class="profkom-history">
            <m3e-divider /><h3>История исключений</h3>
            <m3e-list v-if="history.length" aria-label="История исключений">
              <m3e-list-item v-for="([key, override]) in history" :key="key">
                <m3e-icon slot="leading" name="event_note" />
                <span class="profkom-month">{{ historyMonth(key) }}</span>
                <span slot="supporting-text">{{ describe(override) }}</span>
                <m3e-icon-button slot="trailing" :disabled="busy" :aria-label="`Отменить исключение за ${historyMonth(key)}`" :id="`undo-${key}`" @click="removeOverride(key)"><m3e-icon name="undo" /></m3e-icon-button>
                <m3e-tooltip :for="`undo-${key}`">Отменить исключение</m3e-tooltip>
              </m3e-list-item>
            </m3e-list>
            <p v-else class="profkom-hint">Исключений пока нет</p>
          </div>
        </m3e-card>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.form-title { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.form-title m3e-icon { color: var(--md-sys-color-primary); font-size: 24px; flex: none; }
.months-card { display: block; }
.profkom-page { box-sizing: border-box; height: 100%; overflow: auto; padding: 0 32px 40px; color: var(--md-sys-color-on-surface); }
.profkom-header { position: sticky; top: 0; z-index: 10; margin-bottom: 20px; --m3e-app-bar-container-elevation: none; --m3e-app-bar-container-elevation-on-scroll: none; --m3e-app-bar-container-color: var(--md-sys-color-surface-container-low); --m3e-app-bar-container-color-on-scroll: var(--md-sys-color-surface-container-low); }
h1 { margin: 0; font-size: 32px; font-weight: 400; }
.profkom-card p { color: var(--md-sys-color-on-surface-variant); margin: 4px 0 0; line-height: 1.4; }
.profkom-cards { display: grid; grid-template-columns: minmax(380px, 1fr) minmax(380px, 1fr); align-items: start; gap: 24px; max-width: 1280px; }
.profkom-card { min-width: 0; --m3e-card-padding: 20px; --m3e-card-shape: 24px; --m3e-filled-card-container-color: var(--md-sys-color-surface-container-lowest); }
.profkom-card [slot="header"] { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.profkom-card [slot="footer"] { display: block; margin-top: 16px; }
.profkom-card [slot="header"] > m3e-icon { flex: none; color: var(--md-sys-color-primary); font-size: 24px; }
h2 { font-size: 20px; font-weight: 400; margin: 0; }
h3 { font-size: 14px; font-weight: 500; margin: 0 0 8px 16px; }
.profkom-fields { display: grid; gap: 12px; border: 0; min-width: 0; padding: 0; margin: 0; }
.profkom-toggle { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 40px; cursor: pointer; }
.profkom-toggle m3e-checkbox { flex: none; }
.profkom-hint { font-size: 13px; }
.profkom-month { text-transform: capitalize; }
.reception-months { margin-top: 4px; }
.profkom-history h3 { margin: 16px 0 8px; }
.profkom-history m3e-list { margin-inline: -12px; }
.profkom-history m3e-list-item { --m3e-list-item-container-color: transparent; }
@media (max-width: 1100px) { .profkom-page { padding: 0 20px 32px; } .profkom-cards { gap: 16px; grid-template-columns: 1fr 1fr; } }
@media (max-width: 1000px) { .profkom-cards { grid-template-columns: 1fr; } .profkom-header { align-items: flex-start; flex-direction: column; gap: 16px; } }
@media (max-width: 520px) { .profkom-page { padding: 0 12px 12px; } .profkom-card { --m3e-card-padding: 16px; } }
</style>
