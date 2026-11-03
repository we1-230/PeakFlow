<template>
  <router-link to="/" class="brand-logo" :class="[`size-${size}`, variant]">
    <!-- 全站统一商标：橙色圆角方块 + 白色 A -->
    <BrandMark :size="markSize" :deep="variant === 'dark'" />
    <span class="brand-name">全智能秒杀商城</span>
  </router-link>
</template>

<script setup>
import { computed } from 'vue'
import BrandMark from '@/components/BrandMark.vue'

const props = defineProps({
  size: { type: String, default: 'md' },   // sm | md | lg
  variant: { type: String, default: 'light' } // light=浅色底, dark=深色底
})

const markSize = computed(() => ({ sm: 22, md: 30, lg: 42 }[props.size] || 30))
</script>

<style scoped>
.brand-logo {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  text-decoration: none;
  user-select: none;
  white-space: nowrap;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
}

/* 品牌名：干净的字重与颜色，不用渐变流光（更专业、不廉价） */
.brand-name {
  font-weight: 800;
  letter-spacing: 1px;
  color: #2b2118;
}
.brand-logo.dark .brand-name { color: #fff; }

.size-sm .brand-name { font-size: 16px; }
.size-md .brand-name { font-size: 20px; }
.size-lg .brand-name { font-size: 24px; }
</style>
