<template>
  <span
    class="brand-mark"
    :class="{ 'is-lg': size >= 36 }"
    :style="markStyle"
    aria-hidden="true"
  >A</span>
</template>

<script setup>
import { computed } from 'vue'

// 统一品牌标：橙色圆角方块 + 白色 A（全站唯一商标，各页面复用）
// 注意：模板里不要写根级注释 —— 根级注释会让组件变成 fragment，
// 导致 Vue Test Utils 的 wrapper.element 取到注释节点（读不到内联样式）。

// size: 边长(px)。deep: 深色底上使用（提高对比，仍保持同一商标形象）
const props = defineProps({
  size: { type: Number, default: 30 },
  deep: { type: Boolean, default: false },
})

const markStyle = computed(() => {
  const s = props.size
  return {
    width: s + 'px',
    height: s + 'px',
    fontSize: Math.round(s * 0.54) + 'px',
    borderRadius: Math.round(s * 0.28) + 'px',
    background: props.deep
      ? 'linear-gradient(140deg, #ff5000 0%, #ff7a1a 100%)'
      : 'linear-gradient(140deg, #ff6a00 0%, #ff5000 100%)',
  }
})
</script>

<style scoped>
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #fff;
  font-weight: 800;
  line-height: 1;
  letter-spacing: 0;
  user-select: none;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  box-shadow: 0 2px 8px rgba(255, 80, 0, 0.28);
}
</style>
