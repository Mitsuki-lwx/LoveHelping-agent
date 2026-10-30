<template>
  <div class="img-picker">
    <!-- 触发按钮：与输入区同风格的小圆按钮 -->
    <button
      type="button"
      class="img-btn"
      :disabled="disabled || uploading"
      :title="uploading ? '上传中…' : '发张图片给 TA 看'"
      @click="pick"
    >
      <PhImage :size="18" weight="bold" aria-hidden="true" />
      <span v-if="uploading" class="img-spin" aria-hidden="true"></span>
    </button>
    <input
      ref="fileInput"
      type="file"
      class="img-file"
      accept="image/jpeg,image/png,image/webp"
      multiple
      @change="onPick"
    />

    <!-- 已选图片的缩略图（发送前可逐个删掉） -->
    <div v-if="modelValue.length" class="img-tray">
      <div v-for="m in modelValue" :key="m.id" class="img-thumb">
        <img :src="m.previewUrl" :alt="m.name || '待发送的图片'" />
        <button type="button" class="img-remove" title="移除" @click="remove(m.id)">
          <PhX :size="13" weight="bold" aria-hidden="true" />
        </button>
      </div>
    </div>

    <p v-if="error" class="img-err" role="alert">{{ error }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { PhImage, PhX } from '@phosphor-icons/vue'
import { uploadMedia } from '../api/index.js'

/**
 * 聊天发图（LoveChat / Sandbox 共用一份实现）。
 *
 * ⛔ 为什么做成共用组件：两个聊天处**必须同一套**行为 —— 校验口径、上传接口、失败提示
 *    任何一处不同，用户就会觉得"这个能发那个不能"。
 *
 * ⛔ 契约（与后端 `POST /media/upload` 一致，前端**先校验**再上传，别让用户白等一轮）：
 *    · 类型 jpg/jpeg/png/webp；单张 ≤10MB；一次 ≤4 张；长边 ≤2048px（后端会拒，前端先提示）
 *    · 上传成功拿 mediaId → 发消息时以 **逗号分隔** 传给 SSE（`mediaIds=1,2`）
 */
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
  max: { type: Number, default: 4 },
})
const emit = defineEmits(['update:modelValue'])

const fileInput = ref(null)
const uploading = ref(false)
const error = ref('')

const ALLOWED = ['image/jpeg', 'image/png', 'image/webp']
const MAX_BYTES = 10 * 1024 * 1024
const MAX_EDGE = 2048

function pick() {
  error.value = ''
  fileInput.value?.click()
}

function remove(id) {
  const gone = props.modelValue.find((m) => m.id === id)
  if (gone?.previewUrl) URL.revokeObjectURL(gone.previewUrl)   // 不释放会漏内存（blob 常驻）
  emit('update:modelValue', props.modelValue.filter((m) => m.id !== id))
}

/** 本地先校验：类型/大小/长边/数量。⛔ 长边要**真的读出来**，不能只看文件大小猜。 */
async function validate(file) {
  if (!ALLOWED.includes(file.type)) return '仅支持 JPG / PNG / WebP'
  if (file.size > MAX_BYTES) return `「${file.name}」超过 10MB`
  const edge = await readMaxEdge(file)
  if (edge && edge > MAX_EDGE) return `「${file.name}」长边 ${edge}px 超过 2048px，请先压缩`
  return ''
}

function readMaxEdge(file) {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => { URL.revokeObjectURL(url); resolve(Math.max(img.width, img.height)) }
    img.onerror = () => { URL.revokeObjectURL(url); resolve(0) }
    img.src = url
  })
}

async function onPick(e) {
  const files = [...(e.target.files || [])]
  e.target.value = ''                     // 允许连续选同一个文件
  if (!files.length) return
  if (props.modelValue.length + files.length > props.max) {
    error.value = `一次最多 ${props.max} 张`
    return
  }
  uploading.value = true
  error.value = ''
  try {
    const added = []
    for (const f of files) {
      const bad = await validate(f)
      if (bad) { error.value = bad; continue }   // 单张不合格就跳过，其余照发
      const res = await uploadMedia(f)
      const data = res?.data?.data || res?.data
      if (!data?.id) { error.value = '上传失败，再试一次'; continue }
      added.push({ id: data.id, name: f.name, previewUrl: URL.createObjectURL(f), media: data })
    }
    if (added.length) emit('update:modelValue', [...props.modelValue, ...added])
  } catch (err) {
    error.value = err?.response?.data?.message || '上传失败，再试一次'
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
.img-picker { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.img-btn {
  position: relative;
  display: inline-flex; align-items: center; justify-content: center;
  width: 38px; height: 38px; flex-shrink: 0;
  background: var(--paper-card-warm); border: 1.4px solid oklch(74% 0.024 62);
  border-radius: var(--r-pill); color: var(--ink-on-paper); cursor: pointer;
  transition: border-color 0.16s, transform 0.16s;
}
.img-btn:hover:not(:disabled) { border-color: var(--accent); transform: translateY(-1px); }
.img-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.img-file { display: none; }
.img-spin {
  position: absolute; inset: 2px; border-radius: var(--r-pill);
  border: 2px solid var(--accent); border-top-color: transparent;
  animation: img-spin 0.7s linear infinite;
}
@keyframes img-spin { to { transform: rotate(360deg); } }

.img-tray { display: flex; gap: 8px; flex-wrap: wrap; }
.img-thumb { position: relative; width: 46px; height: 46px; border-radius: var(--r-ctl); overflow: hidden; box-shadow: var(--sh-1); }
.img-thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.img-remove {
  position: absolute; top: 1px; right: 1px;
  width: 17px; height: 17px; padding: 0; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  background: oklch(28% 0.03 30 / 0.82); color: oklch(98% 0.01 80);
  border: none; border-radius: var(--r-seal);
}
.img-err { margin: 0; width: 100%; font-size: 12.5px; color: var(--danger); }
</style>
