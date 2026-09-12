<script setup>
import { onMounted, ref } from 'vue'
import MarkdownIt from 'markdown-it'
import '@m3e/web/expansion-panel'
import { api } from '../api'

const panels = ref([])
const loading = ref(true)
const error = ref('')
const markdown = new MarkdownIt({ html: false, linkify: true })

onMounted(async () => {
  document.title = 'Помощь — Compass Admin'
  try {
    panels.value = (await api('/api/guide')).map(panel => ({
      title: panel.title,
      html: markdown.render(panel.markdown),
    }))
  } catch (cause) { error.value = cause.message }
  finally { loading.value = false }
})
</script>

<template>
  <main class="guide-page">
    <h1>Помощь</h1>
    <p>Ответы на вопросы об использовании Compass Admin</p>
    <div v-if="loading" class="empty-state"><m3e-circular-progress-indicator indeterminate aria-label="Загрузка справки" /></div>
    <div v-else-if="error" class="message error-message" role="alert">{{ error }}</div>
    <m3e-accordion v-else-if="panels.length">
      <m3e-expansion-panel v-for="(panel, index) in panels" :key="index" :open="index === 0">
        <span slot="header">{{ panel.title }}</span>
        <div class="guide-markdown" v-html="panel.html" />
      </m3e-expansion-panel>
    </m3e-accordion>
    <p v-else>Справка пока не заполнена.</p>
  </main>
</template>
