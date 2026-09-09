<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  items: { type: Array, required: true },
  itemHeight: { type: Number, required: true },
  overscan: { type: Number, default: 6 },
})

const viewport = ref()
const scrollTop = ref(0)
const viewportHeight = ref(0)
let observer

const start = computed(() => Math.max(0, Math.floor(scrollTop.value / props.itemHeight) - props.overscan))
const end = computed(() => Math.min(
  props.items.length,
  Math.ceil((scrollTop.value + viewportHeight.value) / props.itemHeight) + props.overscan,
))
const visible = computed(() => props.items.slice(start.value, end.value))

onMounted(() => {
  observer = new ResizeObserver(([entry]) => { viewportHeight.value = entry.contentRect.height })
  observer.observe(viewport.value)
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <div ref="viewport" class="virtual-list" @scroll.passive="scrollTop = $event.target.scrollTop">
    <div class="virtual-list-space" :style="{ height: `${items.length * itemHeight}px` }">
      <div
        v-for="(item, offset) in visible"
        :key="item.id ?? item.fileId"
        class="virtual-list-item"
        :style="{ height: `${itemHeight}px`, transform: `translateY(${(start + offset) * itemHeight}px)` }"
      >
        <slot :item="item" :index="start + offset" />
      </div>
    </div>
  </div>
</template>
