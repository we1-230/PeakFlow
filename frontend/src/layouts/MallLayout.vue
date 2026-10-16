<template>
  <div class="mall-layout">
    <!-- 顶部细栏 -->
    <div class="topbar">
      <div class="topbar-inner">
        <div class="left"></div>
        <div class="right" v-if="auth.isLoggedIn">
          <router-link to="/user" class="topbar-user">
            <img v-if="auth.user?.avatar" :src="auth.user.avatar" :key="auth.user.avatar" class="topbar-avatar" alt="avatar" @error="$event.target.style.display='none'" />
            <span v-else class="topbar-avatar-fallback">{{ (auth.nickname || '用').slice(0, 1) }}</span>
            <span class="topbar-nickname">{{ auth.nickname }}</span>
          </router-link>
          <span class="sep">|</span>
          <a href="javascript:;" @click="handleLogout">退出</a>
          <template v-if="auth.isAdmin">
            <span class="sep">|</span>
            <router-link to="/admin">管理后台</router-link>
          </template>
        </div>
        <div class="right" v-else>
          <router-link to="/login">登录</router-link>
          <span class="sep">|</span>
          <router-link to="/login">注册</router-link>
        </div>
      </div>
    </div>
    <!-- Header -->
    <div class="mall-header">
      <div class="mall-header-inner">
        <BrandLogo size="md" />
        <div class="mall-search">
          <div class="mall-search-box">
            <input v-model="keyword" placeholder="搜索商品" @keyup.enter="doSearch" />
            <button @click="doSearch">搜索</button>
          </div>
        </div>
        <router-link to="/cart" class="mall-cart-btn">
          <el-icon :size="18"><ShoppingCart /></el-icon>
          购物车
          <span class="mall-cart-badge" v-if="cartStore.count > 0">{{ cartStore.count > 99 ? '99+' : cartStore.count }}</span>
        </router-link>
      </div>
    </div>
    <!-- 分类导航（精简为五项，均匀分布） -->
    <div class="cat-nav">
      <div class="cat-nav-inner cat-nav-five">
        <div class="cat-nav-item" :class="{active: $route.path === '/'}" @click="$router.push('/')">首页</div>
        <div class="cat-nav-item" :class="{active: $route.path === '/seckill'}" @click="$router.push('/seckill')">秒杀</div>
        <div class="cat-nav-item" :class="{active: $route.path === '/orders'}" @click="$router.push('/orders')">订单</div>
        <div class="cat-nav-item" :class="{active: $route.path === '/user'}" @click="$router.push('/user')">我的</div>
      </div>
    </div>
    <!-- 主内容 -->
    <div class="mall-main">
      <router-view />
    </div>
    <!-- Footer -->
    <div class="mall-footer">
      <div class="mall-footer-inner">
        <p style="font-size:12px;">© 2026 全智能秒杀商城 · 秒杀架构演示项目</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { Category } from '@/api'
import BrandLogo from '@/components/BrandLogo.vue'

const router = useRouter()
const auth = useAuthStore()
const cartStore = useCartStore()
const keyword = ref('')
const categories = ref([])

async function loadCategories() {
  try { categories.value = await Category.list() } catch {}
}
function doSearch() {
  // 关键词规范化：全角空格→半角、连续空白→单空格，确保后端多词模糊匹配正确触发
  const kw = (keyword.value || '').replace(/\u3000/g, ' ').replace(/[ \t]+/g, ' ').trim()
  if (kw) router.push({ path: '/products', query: { keyword: kw } })
}
function handleLogout() {
  auth.logout()
  router.push('/')
}
onMounted(() => { loadCategories(); if (auth.isLoggedIn) cartStore.refresh() })
</script>

<style scoped>
/* 导航项均匀分布 */
.cat-nav-five { display:flex !important; justify-content: space-around !important; }
.cat-nav-five .cat-nav-item { font-size: 15px; font-weight: 600; padding: 8px 24px; }
</style>
