<script setup>
import { computed, ref } from 'vue'
import { api } from '../api'

const topics = { general: 'Общее', schedule: 'Расписание', important: 'Важно' }
const mode = ref('topic')
const topic = ref('general')
const fcmToken = ref('')
const title = ref('')
const body = ref('')
const dialog = ref()
const sending = ref(false)
const error = ref('')
const sent = ref(false)
const canSend = computed(() => Boolean(title.value && body.value && (mode.value === 'topic' ? topic.value : fcmToken.value)))

defineExpose({ beforeLeave: () => !sending.value })

function confirmSend() {
  if (!sending.value && canSend.value) dialog.value.showModal()
}

async function send() {
  if (sending.value || !canSend.value) return
  dialog.value.close()
  sending.value = true
  error.value = ''
  sent.value = false

  try {
    await api('/api/notifications', {
      method: 'POST',
      body: JSON.stringify({
        title: title.value,
        body: body.value,
        ...(mode.value === 'topic' ? { topic: topic.value } : { fcmToken: fcmToken.value }),
      }),
    })
    title.value = ''
    body.value = ''
    sent.value = true
  } catch (cause) {
    error.value = cause.message
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <section class="notifications-page">
    <m3e-card variant="filled" class="notifications-card">
      <form slot="content" class="dialog-form" @submit.prevent="confirmSend">
        <h1>Отправить уведомление</h1>
        <div>
          <label id="notification-recipient-label">Получатель</label>
          <m3e-radio-group aria-labelledby="notification-recipient-label" :disabled="sending" @change="mode = $event.currentTarget.value">
            <label><m3e-radio value="topic" :checked="mode === 'topic'" />По топику</label>
            <label><m3e-radio value="token" :checked="mode === 'token'" />По токену</label>
          </m3e-radio-group>
        </div>
        <div v-if="mode === 'topic'">
          <label id="notification-topic-label">Топик</label>
          <m3e-radio-group aria-labelledby="notification-topic-label" :disabled="sending" @change="topic = $event.currentTarget.value">
            <label v-for="(label, value) in topics" :key="value">
              <m3e-radio :value="value" :checked="topic === value" />{{ label }}
            </label>
          </m3e-radio-group>
        </div>
        <m3e-form-field v-else variant="outlined" hide-subscript="always">
          <label slot="label" for="notification-token">Токен FCM</label>
          <input id="notification-token" v-model.trim="fcmToken" :disabled="sending" autocomplete="off" spellcheck="false" required />
        </m3e-form-field>
        <m3e-form-field variant="outlined" hide-subscript="always">
          <label slot="label" for="notification-title">Заголовок</label>
          <input id="notification-title" v-model.trim="title" :disabled="sending" required />
        </m3e-form-field>
        <m3e-form-field variant="outlined" hide-subscript="always">
          <label slot="label" for="notification-body">Содержимое</label>
          <textarea id="notification-body" v-model.trim="body" rows="4" :disabled="sending" required />
        </m3e-form-field>
        <div v-if="error" class="message error-message" role="alert">{{ error }}</div>
        <div v-if="sent" class="message success-message" role="status">FCM принял уведомление</div>
        <m3e-button type="submit" variant="filled" :disabled="!canSend || sending">
          {{ sending ? 'Отправка…' : 'Отправить' }}
        </m3e-button>
      </form>
    </m3e-card>
    <dialog ref="dialog" class="app-dialog" aria-labelledby="notification-confirm-title">
      <h2 id="notification-confirm-title">Отправить уведомление?</h2>
      <p v-if="mode === 'topic'">Уведомление будет отправлено всем подписчикам топика «{{ topics[topic] }}».</p>
      <p v-else>Уведомление будет отправлено на устройство с указанным FCM-токеном.</p>
      <div class="dialog-actions">
        <m3e-button variant="text" @click="dialog.close()">Отмена</m3e-button>
        <m3e-button variant="filled" @click="send">Отправить</m3e-button>
      </div>
    </dialog>
  </section>
</template>

<style scoped>
.notifications-page { box-sizing: border-box; height: 100%; overflow: auto; padding: 20px clamp(12px, 4vw, 32px); color: var(--md-sys-color-on-surface); }
.notifications-card { display: block; max-width: 640px; --m3e-card-padding: 20px; --m3e-card-shape: 24px; --m3e-filled-card-container-color: var(--md-sys-color-surface-container-lowest); }
h1 { margin: 0; font-size: 22px; font-weight: 400; }
m3e-radio-group { display: flex; flex-wrap: wrap; gap: 0 8px; }
m3e-radio-group label { display: inline-flex; align-items: center; }
m3e-form-field { min-width: 0; }
textarea { font: inherit; resize: vertical; }
form > m3e-button { justify-self: end; }
.message { margin: 0; }
</style>
