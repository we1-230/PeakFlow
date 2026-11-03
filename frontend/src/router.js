import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const MallLayout = () => import('@/layouts/MallLayout.vue')
const AdminLayout = () => import('@/layouts/AdminLayout.vue')

const routes = [
  {
    path: '/',
    component: MallLayout,
    children: [
      { path: '', name: 'home', component: () => import('@/views/mall/Home.vue') },
      { path: 'products', name: 'products', component: () => import('@/views/mall/ProductList.vue') },
      { path: 'product/:id', name: 'productDetail', component: () => import('@/views/mall/ProductDetail.vue') },
      { path: 'seckill', name: 'seckill', component: () => import('@/views/mall/Seckill.vue') },
      { path: 'cart', name: 'cart', component: () => import('@/views/mall/Cart.vue') },
      { path: 'checkout', name: 'checkout', component: () => import('@/views/mall/Checkout.vue'), meta: { requiresAuth: true } },
      { path: 'orders', name: 'orders', component: () => import('@/views/mall/OrderList.vue'), meta: { requiresAuth: true } },
      { path: 'order/:id', name: 'orderDetail', component: () => import('@/views/mall/OrderDetail.vue'), meta: { requiresAuth: true } },
      { path: 'address', name: 'address', component: () => import('@/views/mall/Address.vue'), meta: { requiresAuth: true } },
      { path: 'user', name: 'user', component: () => import('@/views/mall/UserCenter.vue'), meta: { requiresAuth: true } },
    ],
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/mall/Login.vue'),
  },
  {
    path: '/admin/login',
    name: 'adminLogin',
    component: () => import('@/views/admin/AdminLogin.vue'),
  },
  {
    path: '/admin',
    component: AdminLayout,
    meta: { requiresAdmin: true },
    children: [
      { path: '', name: 'dashboard', component: () => import('@/views/admin/Dashboard.vue') },
      { path: 'users', name: 'adminUsers', component: () => import('@/views/admin/UserManage.vue') },
      { path: 'products', name: 'adminProducts', component: () => import('@/views/admin/ProductManage.vue') },
      { path: 'categories', name: 'adminCategories', component: () => import('@/views/admin/CategoryManage.vue') },
      { path: 'seckill', name: 'adminSeckill', component: () => import('@/views/admin/SeckillManage.vue') },
      { path: 'orders', name: 'adminOrders', component: () => import('@/views/admin/OrderManage.vue') },
      { path: 'refunds', name: 'adminRefunds', component: () => import('@/views/admin/RefundManage.vue') },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() { return { top: 0 } },
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'adminLogin' }
  }
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})

export default router
