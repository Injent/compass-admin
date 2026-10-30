<script setup>
const props = defineProps({ value: Object, disabled: Boolean, prefix: String, dayErrors: Object, maxDay: { type: Number, default: 31 }, splitCards: Boolean })
const weekdays = { monday: 'Понедельник', tuesday: 'Вторник', wednesday: 'Среда', thursday: 'Четверг', friday: 'Пятница', saturday: 'Суббота', sunday: 'Воскресенье' }
const active = key => !props.disabled && (props.prefix !== 'override' || key === 'enabled' || props.value.enabled)
function formatTime(event) {
  const digits = event.target.value.replace(/\D/g, '').slice(0, 4)
  event.target.value = digits.length > 2 ? `${digits.slice(0, 2)}:${digits.slice(2)}` : digits
  event.target.dispatchEvent(new Event('change', { bubbles: true }))
}
function finishTime(event, day, side) {
  const digits = event.target.value.replace(/\D/g, '')
  if (digits.length === 3) day[side] = `0${digits[0]}:${digits.slice(1)}`
  else if (digits.length === 4) day[side] = `${digits.slice(0, 2)}:${digits.slice(2)}`
}
function timeError(day) {
  return day.enabled && (!/^([01]\d|2[0-3]):[0-5]\d$/.test(day.from) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(day.to) || day.from >= day.to) ? 'Начало должно быть раньше окончания' : ''
}
function validity(input, { value }) { input.setCustomValidity(value); input.closest('m3e-form-field')?.notifyControlStateChange(true) }
const vValidation = { mounted: validity, updated: validity }
</script>

<template>
  <div class="schedule-form">
    <section>
      <component :is="splitCards ? 'm3e-card' : 'div'" class="section-content" :class="{ padded: splitCards }">
      <div :slot="splitCards ? 'content' : undefined">
      <label class="checkbox-row"><m3e-checkbox :checked="value.enabled" :disabled="!active('enabled')" :aria-label="`${prefix}: приём заявлений открыт`" @change="value.enabled = $event.target.checked" />Приём заявлений открыт</label>
      </div>
      </component>
    </section>
    <section>
      <h3>Числа месяца приема</h3>
      <component :is="splitCards ? 'm3e-card' : 'div'" class="section-content" :class="{ padded: splitCards }">
      <div :class="{ 'compact-content': splitCards }">
      <div class="day-fields">
        <m3e-form-field v-for="(label, side) in { from: 'С числа', to: 'По число' }" :key="side" variant="outlined">
          <label slot="label" :for="`${prefix}-day-${side}`">{{ label }}</label>
          <input :id="`${prefix}-day-${side}`" v-model.number="value.dayRange[side]" v-validation="dayErrors[side]" type="number" min="1" :max="maxDay" step="1" :disabled="!active('dayRange')" required />
          <span slot="error">{{ dayErrors[side] }}</span>
        </m3e-form-field>
      </div>
      </div>
      </component>
    </section>
    <section>
      <h3>Время приема</h3>
      <component :is="splitCards ? 'm3e-card' : 'div'" class="section-content" :class="{ padded: splitCards }">
      <div :class="{ 'compact-content': splitCards }">
      <div v-for="(label, key) in weekdays" :key="key" class="workday">
        <label class="checkbox-row"><m3e-checkbox :checked="value.hours.days[key].enabled" :disabled="!active('hours')" :aria-label="`${prefix}: ${label}`" @change="value.hours.days[key].enabled = $event.target.checked" />{{ label }}</label>
        <template v-for="(timeLabel, side) in { from: 'Начало', to: 'Окончание' }" :key="side">
        <span v-if="side === 'to'" class="time-dash" aria-hidden="true">–</span>
        <m3e-form-field variant="outlined" class="time-field" hide-subscript="always">
          <input v-model="value.hours.days[key][side]" v-validation="active('hours') && side === 'to' ? timeError(value.hours.days[key]) : ''" type="text" inputmode="numeric" maxlength="5" placeholder="ЧЧ:ММ" @input="formatTime($event); value.hours.days[key][side] = $event.target.value" @blur="finishTime($event, value.hours.days[key], side)" :aria-label="`${prefix}: ${label}, ${timeLabel}`" :disabled="!active('hours') || !value.hours.days[key].enabled" required />
        </m3e-form-field>
        </template>
        <span v-if="active('hours') && timeError(value.hours.days[key])" class="time-error" role="alert">{{ timeError(value.hours.days[key]) }}</span>
      </div>
      </div>
      </component>
    </section>
  </div>
</template>

<style scoped>
.compact-content { padding: 20px 20px 8px; }
.section-content { display: block; }
.padded { --m3e-card-padding: 20px; --m3e-filled-card-container-color: var(--md-sys-color-surface-container-lowest); --m3e-card-shape: 24px; }
.schedule-form { display: grid; gap: 16px; min-width: 0; }
h3 { font-size: 14px; font-weight: 500; margin: 0 0 8px 16px; }
.checkbox-row, .section-heading { display: flex; align-items: center; gap: 4px; min-height: 36px; cursor: pointer; }
.section-heading { font-size: 14px; font-weight: 500; margin-bottom: 4px; }
m3e-checkbox { flex: none; --md-sys-density-scale: -3; }
m3e-form-field { --md-sys-density-scale: -3; min-width: 0; width: 100%; }
.day-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 112px)); gap: 12px; margin-top: 8px; }
.day-fields.dates { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.workday { display: grid; grid-template-columns: minmax(130px, 1fr) 92px 12px 92px; align-items: start; gap: 8px; margin-bottom: 6px; }
.time-field { --m3e-form-field-font-size: 14px; }
.time-field input::-webkit-calendar-picker-indicator { display: none; }
.time-dash { align-self: center; text-align: center; }
.time-error { grid-column: 2 / -1; color: var(--md-sys-color-error); font-size: 12px; }
m3e-tabs { min-width: 0; }
m3e-tab-panel { flex: 0 0 100%; min-width: 0; width: 100%; overflow-x: hidden; }
@media (max-width: 450px) { .workday { grid-template-columns: minmax(100px, 1fr) 78px 8px 78px; gap: 4px; font-size: 13px; } .day-fields.dates { grid-template-columns: 1fr; } }
</style>
