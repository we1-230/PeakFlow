<template>
  <div class="admin-layout" v-if="canEnter">
    <!-- ======== 侧边栏 ======== -->
    <aside class="admin-sidebar" :class="{ collapsed }">
      <!-- 品牌区 -->
      <div class="sidebar-brand" @click="router.push('/admin')">
        <!-- 全站统一商标：橙色圆角方块 + 白色 A -->
        <BrandMark :size="38" deep />
        <div class="brand-text" v-show="!collapsed">
          <div class="brand-name">全智能秒杀商城</div>
          <div class="brand-sub">管理后台</div>
        </div>
      </div>

      <!-- 菜单 -->
      <nav class="sidebar-menu">
        <template v-for="group in visibleGroups" :key="group.label">
          <div class="menu-group-label" v-show="!collapsed">{{ group.label }}</div>
          <div
            v-for="m in group.items"
            :key="m.path"
            class="menu-item"
            :class="{ active: isActive(m.path), 'menu-item--icon': collapsed }"
            @click="router.push(m.path)"
            :title="collapsed ? m.label : ''"
          >
            <el-icon :size="18" class="menu-icon"><component :is="m.icon" /></el-icon>
            <span class="menu-label" v-show="!collapsed">{{ m.label }}</span>
          </div>
        </template>
      </nav>

      <!-- 底部 -->
      <div class="sidebar-footer">
        <div class="menu-item" @click="router.push('/')" :title="'返回商城'">
          <el-icon :size="18"><Shop /></el-icon>
          <span class="menu-label" v-show="!collapsed">返回商城</span>
        </div>
        <div class="menu-item" @click="handleLogout">
          <el-icon :size="18"><SwitchButton /></el-icon>
          <span class="menu-label" v-show="!collapsed">退出登录</span>
        </div>
      </div>
    </aside>

    <!-- ======== 主区域 ======== -->
    <div class="admin-main">
      <!-- 顶栏 -->
      <header class="admin-header">
        <div class="header-left">
          <el-icon class="collapse-btn" :size="20" @click="collapsed = !collapsed">
            <Expand v-if="collapsed" /><Fold v-else />
          </el-icon>
          <div class="header-title">
            <span class="page-title">{{ currentTitle }}</span>
            <span class="page-crumb">管理后台 / {{ currentTitle }}</span>
          </div>
        </div>
        <div class="header-right">
          <el-tooltip content="刷新数据" placement="bottom">
            <el-icon class="header-action" :size="18" @click="emit('refresh')"><Refresh /></el-icon>
          </el-tooltip>
          <div class="admin-user">
            <span class="admin-avatar">{{ (auth.nickname || '管').slice(0, 1) }}</span>
            <div class="user-info">
              <span class="user-name">{{ auth.nickname || '管理员' }}</span>
              <span class="user-role">{{ roleLabel }}</span>
            </div>
          </div>
          <el-button type="danger" plain size="small" @click="handleLogout">退出</el-button>
        </div>
      </header>

      <!-- 内容区 -->
      <main class="admin-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'
import {
  Odometer, Goods, Menu, Timer, User, List, Shop, SwitchButton,
  Expand, Fold, Refresh, RefreshLeft,
} from '@element-plus/icons-vue'
import BrandMark from '@/components/BrandMark.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const emit = defineEmits(['refresh'])

// 双保险：即使绕过路由守卫（直接改 URL / 旧 token）进入 /admin，
// 这里再次按后端权限码校验；非后台角色立即踢回登录页，且本布局内的菜单/数据一律不渲染。
const canEnter = computed(() => auth.isAdmin)

onMounted(() => {
  if (!canEnter.value) {
    ElMessage.error('无管理员权限，已退出后台')
    auth.logout()
    router.replace('/admin/login')
  }
})

const collapsed = ref(false)

// 移动端适配：窄屏首次进入后台自动折叠侧栏为图标模式（复用桌面已有的折叠逻辑，
// 用户仍可点顶栏按钮展开；>768px 的桌面行为完全不变）
if (typeof window !== 'undefined' && window.innerWidth <= 768) {
  collapsed.value = true
}

// 菜单按权限点声明：只展示当前账号有权访问的入口（超级管理员拥有全部权限，自然全显示）
const menuGroups = [
  {
    label: '总览',
    items: [
      { path: '/admin', label: '仪表盘', icon: Odometer, perm: 'stats:view' },
    ],
  },
  {
    label: '商品运营',
    items: [
      { path: '/admin/products', label: '商品管理', icon: Goods, perm: 'product:manage' },
      { path: '/admin/categories', label: '分类管理', icon: Menu, perm: 'category:manage' },
      { path: '/admin/seckill', label: '秒杀管理', icon: Timer, perm: 'seckill:manage' },
    ],
  },
  {
    label: '用户与订单',
    items: [
      { path: '/admin/users', label: '用户管理', icon: User, perm: 'user:manage' },
      { path: '/admin/orders', label: '订单管理', icon: List, perm: 'order:read' },
      { path: '/admin/refunds', label: '退款管理', icon: RefreshLeft, perm: 'refund:read' },
    ],
  },
]

const visibleGroups = computed(() =>
  menuGroups
    .map(g => ({ ...g, items: g.items.filter(m => auth.hasPermission(m.perm)) }))
    .filter(g => g.items.length > 0)
)

// 顶栏角色标签：按权限画像推断（不再写死“超级管理员”，否则商品运营/订单客服登录后会显示错误角色）
const roleLabel = computed(() => {
  const has = (p) => auth.hasPermission(p)
  if (has('user:manage')) return '超级管理员'
  if (has('product:manage') && !has('order:read')) return '商品运营'
  if (has('order:read') && !has('product:manage')) return '订单客服'
  return '运营'
})

const flatMenus = computed(() => visibleGroups.value.flatMap(g => g.items))
const currentTitle = computed(() => flatMenus.value.find(m => route.path === m.path)?.label || '管理后台')

function isActive(path) {
  return route.path === path
}

function handleLogout() {
  auth.logout()
  router.push('/admin/login')
}
</script>

<style scoped>
.admin-layout {
  display: flex;
  min-height: 100vh;
  background: #f3f5f9;
}

/* ============ 侧边栏 ============ */
.admin-sidebar {
  width: 232px;
  flex-shrink: 0;
  background: linear-gradient(180deg, #141b2d 0%, #1a2337 60%, #1e2a40 100%);
  color: #a89a8f;
  display: flex;
  flex-direction: column;
  transition: width .25s ease;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: hidden;
  box-shadow: 2px 0 12px rgba(15, 23, 42, .18);
}
.admin-sidebar.collapsed { width: 64px; }

/* 品牌区 */
.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 16px 18px;
  cursor: pointer;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
.brand-name { font-size: 15px; font-weight: 800; color: #fff; letter-spacing: 1px; white-space: nowrap; }
.brand-sub { font-size: 11px; color: #83766c; letter-spacing: 3px; margin-top: 2px; }

/* 菜单 */
.sidebar-menu { flex: 1; padding: 10px 10px; overflow-y: auto; }
.sidebar-menu::-webkit-scrollbar { width: 4px; }
.sidebar-menu::-webkit-scrollbar-thumb { background: rgba(255,255,255,.12); border-radius: 2px; }
.menu-group-label {
  font-size: 11px;
  color: #63574e;
  letter-spacing: 2px;
  padding: 14px 12px 6px;
  user-select: none;
}
.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  margin: 2px 0;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: all .18s;
  white-space: nowrap;
  position: relative;
}
.menu-item:hover { background: rgba(255, 255, 255, 0.06); color: #e6ded6; }
.menu-item.active {
  background: linear-gradient(90deg, rgba(255,80,0, 0.22), rgba(255,80,0, 0.06));
  color: #ffc9a0;
  font-weight: 600;
}
.menu-item.active::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  border-radius: 0 3px 3px 0;
  background: linear-gradient(180deg, #ff5000, #ff8a3d);
  box-shadow: 0 0 8px rgba(255,80,0, 0.7);
}
.menu-icon { flex-shrink: 0; }
.menu-item--icon { justify-content: center; padding: 11px 0; }

/* 底部 */
.sidebar-footer { padding: 10px; border-top: 1px solid rgba(255,255,255,.06); }

/* ============ 主区域 ============ */
.admin-main { flex: 1; display: flex; flex-direction: column; min-width: 0; }

.admin-header {
  height: 60px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.06);
  position: sticky;
  top: 0;
  z-index: 10;
}
.header-left { display: flex; align-items: center; gap: 14px; }
.collapse-btn { cursor: pointer; color: #8a7d73; transition: color .2s; }
.collapse-btn:hover { color: #ff5000; }
.header-title { display: flex; flex-direction: column; gap: 1px; }
.page-title { font-size: 16px; font-weight: 700; color: #2e2620; }
.page-crumb { font-size: 11px; color: #a89a8f; }
.header-right { display: flex; align-items: center; gap: 16px; }
.header-action { cursor: pointer; color: #8a7d73; transition: color .2s; }
.header-action:hover { color: #ff5000; transform: rotate(180deg); }
.admin-user { display: flex; align-items: center; gap: 9px; }
.admin-avatar {
  width: 34px; height: 34px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ff5000, #ff7a1a);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 3px 10px rgba(255,80,0, 0.35);
}
.user-info { display: flex; flex-direction: column; line-height: 1.2; }
.user-name { font-size: 13px; font-weight: 600; color: #3a2e26; }
.user-role { font-size: 11px; color: #a89a8f; }

/* 内容 */
.admin-content { flex: 1; padding: 20px; overflow: auto; }
</style>
