<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import VirtualList from '../components/VirtualList.vue'

const teachers = ref([])
const search = ref('')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const deleteMode = ref(false)
const selected = ref(new Set())
const editor = ref()
const draft = ref(emptyTeacher())
const initialDraft = ref('')

const filtered = computed(() => {
  const query = search.value.trim().toLocaleLowerCase('ru')
  return query ? teachers.value.filter(teacher => teacher.fullName.toLocaleLowerCase('ru').includes(query)) : teachers.value
})
const canSave = computed(() => draft.value.lastName.trim() && draft.value.firstName.trim() && JSON.stringify(draft.value) !== initialDraft.value)

function emptyTeacher() {
  return { id: null, lastName: '', firstName: '', middleName: '', departments: '' }
}
async function load() {
  try { teachers.value = await api('/api/teachers') }
  catch (cause) { error.value = cause.message }
  finally { loading.value = false }
}
function openEditor(teacher = emptyTeacher()) {
  draft.value = { ...teacher }
  initialDraft.value = JSON.stringify(draft.value)
  editor.value.showModal()
}
function toggle(id) {
  const next = new Set(selected.value)
  next.has(id) ? next.delete(id) : next.add(id)
  selected.value = next
}
function leaveDeleteMode() {
  deleteMode.value = false
  selected.value = new Set()
}
async function save() {
  if (!canSave.value) return
  busy.value = true
  const body = JSON.stringify(draft.value)
  try {
    const saved = draft.value.id == null
      ? await api('/api/teachers', { method: 'POST', body })
      : await api(`/api/teachers/${draft.value.id}`, { method: 'PUT', body })
    const index = teachers.value.findIndex(item => item.id === saved.id)
    if (index < 0) teachers.value.push(saved)
    else teachers.value[index] = saved
    teachers.value.sort((a, b) => a.fullName.localeCompare(b.fullName, 'ru'))
    editor.value.close()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}
async function removeOne() {
  if (draft.value.id == null) return
  busy.value = true
  try {
    await api(`/api/teachers/${draft.value.id}`, { method: 'DELETE' })
    teachers.value = teachers.value.filter(item => item.id !== draft.value.id)
    editor.value.close()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}
async function removeSelected() {
  if (!selected.value.size) return
  busy.value = true
  try {
    await api('/api/teachers/delete', { method: 'POST', body: JSON.stringify({ ids: [...selected.value] }) })
    teachers.value = teachers.value.filter(item => !selected.value.has(item.id))
    leaveDeleteMode()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <div class="page-shell">
    <div class="schedule-header">
      <m3e-search-bar id="teacher-search-bar" clearable @clear="search = ''">
        <m3e-icon name="search" slot="leading" />
        <input v-model="search" slot="input" placeholder="Поиск преподавателей" />
      </m3e-search-bar>
      <div class="actions-wrapper">
        <m3e-button v-if="deleteMode" @click="leaveDeleteMode"><m3e-icon slot="icon" name="close" />Отмена</m3e-button>
        <m3e-button :disabled="busy || (deleteMode && !selected.size)" @click="deleteMode ? removeSelected() : openEditor()">
          <m3e-icon slot="icon" :name="deleteMode ? 'delete' : 'add'" />
          {{ deleteMode ? `Удалить ${selected.size}/${filtered.length}` : 'Добавить' }}
        </m3e-button>
        <m3e-button v-if="!deleteMode" variant="outlined" @click="deleteMode = true"><m3e-icon slot="icon" name="delete" />Удалить</m3e-button>
      </div>
    </div>

    <m3e-content-pane class="schedule-pane">
      <div class="list-page-content">
        <div v-if="error" class="message error-message"><m3e-icon name="error" />{{ error }}</div>
        <div class="table-heading teacher-grid"><span>№</span><b>ФИО</b><span /></div>
        <m3e-divider />
        <div v-if="loading" class="empty-state"><m3e-circular-progress-indicator indeterminate /></div>
        <VirtualList v-else-if="filtered.length" :items="filtered" :item-height="64" class="page-virtual-list">
          <template #default="{ item: teacher, index }">
            <m3e-list-action class="virtual-action" @click="deleteMode ? toggle(teacher.id) : openEditor(teacher)">
              <div class="teacher-grid">
                <strong>{{ index + 1 }}</strong>
                <span>{{ teacher.fullName }}</span>
                <m3e-checkbox v-if="deleteMode" :checked="selected.has(teacher.id)" @click.stop="toggle(teacher.id)" />
                <m3e-icon-button v-else aria-label="Редактировать" @click.stop="openEditor(teacher)"><m3e-icon name="edit" /></m3e-icon-button>
              </div>
            </m3e-list-action>
          </template>
        </VirtualList>
        <div v-else class="empty-state"><m3e-icon name="co_present" /><m3e-heading variant="headline" size="small">Преподаватели не найдены</m3e-heading></div>
      </div>
    </m3e-content-pane>

    <dialog ref="editor" class="app-dialog teacher-dialog">
      <h2>{{ draft.id == null ? 'Новый преподаватель' : 'Редактирование' }}</h2>
      <form class="dialog-form" @submit.prevent="save">
        <m3e-form-field variant="outlined"><label slot="label" for="last-name">Фамилия</label><input id="last-name" v-model="draft.lastName" required /></m3e-form-field>
        <m3e-form-field variant="outlined"><label slot="label" for="first-name">Имя</label><input id="first-name" v-model="draft.firstName" required /></m3e-form-field>
        <m3e-form-field variant="outlined"><label slot="label" for="middle-name">Отчество</label><input id="middle-name" v-model="draft.middleName" /></m3e-form-field>
        <m3e-form-field variant="outlined"><label slot="label" for="departments">Подразделения</label><input id="departments" v-model="draft.departments" /></m3e-form-field>
        <div class="dialog-actions">
          <m3e-button v-if="draft.id != null" type="button" variant="outlined" :disabled="busy" @click="removeOne">Удалить</m3e-button>
          <span class="dialog-spacer" />
          <m3e-button type="button" @click="editor.close()">Отмена</m3e-button>
          <m3e-button type="submit" variant="filled" :disabled="busy || !canSave">Сохранить</m3e-button>
        </div>
      </form>
    </dialog>
  </div>
</template>
