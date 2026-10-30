<script setup>
import { computed, ref } from 'vue'
const props = defineProps({ dates: Array, month: String, disabled: Boolean, prefix: String })
const emit = defineEmits(['update:dates'])
const input = ref('')
const parsed = computed(() => {
  const pattern = props.month ? /^(\d{1,2})$/ : /^(\d{2})\.(\d{2})$/
  const match = input.value.match(pattern)
  if (!match) return null
  const day = Number(match[1])
  const [year, month] = props.month ? props.month.split('-').map(Number) : [2000, Number(match[2])]
  const date = new Date(year, month - 1, day)
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return null
  const suffix = `${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
  return props.month ? `${year}-${suffix}` : suffix
})
const error = computed(() => !input.value ? '' : !parsed.value ? props.month ? `Введите число от 1 до ${new Date(...props.month.split('-').map(Number), 0).getDate()}` : 'Введите существующую дату: ДД.ММ' : props.dates.includes(parsed.value) ? 'Дата уже добавлена' : '')
function formatInput(event) {
  const digits = event.target.value.replace(/\D/g, '').slice(0, props.month ? 2 : 4)
  input.value = !props.month && digits.length > 2 ? `${digits.slice(0, 2)}.${digits.slice(2)}` : digits
  event.target.value = input.value
}
function add() { if (!parsed.value || error.value || props.disabled) return; emit('update:dates', [...props.dates, parsed.value].sort()); input.value = '' }
function label(value) {
  const date = new Date(`${value.length === 5 ? '2000-' : ''}${value}T12:00:00`)
  const dayMonth = date.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })
  return value.length === 5 ? dayMonth : `${dayMonth} ${date.getFullYear()}`
}
</script>
<template>
  <div class="date-entry">
    <m3e-form-field variant="outlined" hide-subscript="always">
      <label slot="label" :for="`${prefix}-excluded-date`">{{ month ? 'Число месяца' : 'Дата ежегодно' }}</label>
      <input :id="`${prefix}-excluded-date`" :value="input" @input="formatInput" :disabled="disabled" :placeholder="month ? 'ДД' : 'ДД.ММ'" :maxlength="month ? 2 : 5" inputmode="numeric" @keydown.enter.prevent="add" />
    </m3e-form-field>
    <m3e-icon-button :disabled="disabled || !parsed || !!error" aria-label="Добавить нерабочую дату" @click="add"><m3e-icon name="add" /></m3e-icon-button>
  </div>
  <p v-if="error" class="date-error" role="alert">{{ error }}</p>
  <m3e-list v-if="dates.length" aria-label="Нерабочие даты">
    <m3e-list-item v-for="date in dates" :key="date">
      {{ label(date) }}
      <m3e-icon-button slot="trailing" :disabled="disabled" :aria-label="`Удалить ${label(date)}`" @click="emit('update:dates', dates.filter(value => value !== date))"><m3e-icon name="close" /></m3e-icon-button>
    </m3e-list-item>
  </m3e-list>
</template>
<style scoped>
.date-entry { display: flex; align-items: center; gap: 8px; }
m3e-form-field { width: min(220px, 100%); min-width: 0; --md-sys-density-scale: -3; }
.date-error { color: var(--md-sys-color-error); font-size: 12px; margin: 0; }
m3e-list { --m3e-list-item-container-color: transparent; }
</style>
