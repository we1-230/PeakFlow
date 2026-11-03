<template>
  <div class="mall-main cart-page">
    <!-- 未登录 -->
    <div v-if="!auth.isLoggedIn" class="empty-state">
      <p>请先登录后再查看购物车</p>
      <el-button type="primary" class="btn-primary" @click="goLogin">去登录</el-button>
    </div>

    <template v-else>
      <div class="section-title">我的购物车</div>

      <!-- 加载中 -->
      <div v-if="loading" class="loading-state">加载中...</div>

      <!-- 空购物车 -->
      <div v-else-if="items.length === 0" class="empty-state">
        <p>购物车还是空的，快去挑选心仪的商品吧</p>
        <el-button type="primary" class="btn-primary" @click="router.push('/')">去逛逛</el-button>
      </div>

      <template v-else>
        <!-- 购物车表格 -->
        <table class="cart-table">
          <thead>
            <tr>
              <th class="col-check">
                <el-checkbox
                  :model-value="isAllSelected"
                  :indeterminate="isIndeterminate"
                  @change="handleSelectAll"
                >全选</el-checkbox>
              </th>
              <th class="col-product">商品</th>
              <th class="col-price">单价</th>
              <th class="col-qty">数量</th>
              <th class="col-subtotal">小计</th>
              <th class="col-action">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.productId">
              <td class="col-check">
                <el-checkbox
                  :model-value="Number(item.selected) === 1"
                  @change="handleSelect(item)"
                />
              </td>
              <td class="col-product">
                <div class="product-cell" @click="goProduct(item.productId)">
                  <img v-if="item.imageUrl" :src="resolveImage(item.imageUrl, IMG_SIZE.mini)" class="product-img" loading="lazy" decoding="async" :alt="item.productName" @error="$event.target.style.display='none'" />
                  <div v-else class="img-placeholder">{{ (item.productName || '?').slice(0, 1) }}</div>
                  <span class="product-name">{{ item.productName }}</span>
                </div>
              </td>
              <td class="col-price">
                <span class="price-display"><span class="symbol">¥</span>{{ Number(item.price ?? 0).toFixed(2) }}</span>
              </td>
              <td class="col-qty">
                <div class="qty-stepper">
                  <button type="button" :disabled="item.quantity <= 1" @click="handleQtyChange(item, item.quantity - 1)">−</button>
                  <input type="number" :value="item.quantity" @change="handleQtyInput(item, $event)" />
                  <button type="button" @click="handleQtyChange(item, item.quantity + 1)">+</button>
                </div>
              </td>
              <td class="col-subtotal">
                <span class="price-display"><span class="symbol">¥</span>{{ (Number(item.price ?? 0) * item.quantity).toFixed(2) }}</span>
              </td>
              <td class="col-action">
                <el-button link type="danger" @click="handleRemove(item)">删除</el-button>
              </td>
            </tr>
          </tbody>
        </table>

        <!-- 底部结算栏 -->
        <div class="cart-footer">
          <div class="footer-left">
            <el-checkbox
              :model-value="isAllSelected"
              :indeterminate="isIndeterminate"
              @change="handleSelectAll"
            >全选</el-checkbox>
            <span class="footer-count">已选 <b>{{ selectedCount }}</b> 件商品</span>
          </div>
          <div class="footer-right">
            <span class="footer-total">
              合计：<span class="price-display"><span class="symbol">¥</span><span class="amount">{{ totalPrice.toFixed(2) }}</span></span>
            </span>
            <button class="btn-primary btn-large checkout-btn" :disabled="selectedCount === 0" @click="handleCheckout">结 算</button>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { resolveImage, IMG_SIZE } from '@/utils/image'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Cart } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const cartStore = useCartStore()

const items = ref([])
const loading = ref(false)

const loadCart = async () => {
  if (!auth.isLoggedIn) return
  loading.value = true
  try {
    // 后端返回结构: { code, data: [...] } 或直接数组 —— 做兼容
    const res = await Cart.list()
    let list = []
    if (Array.isArray(res)) list = res
    else if (res && Array.isArray(res.data)) list = res.data
    else if (res && Array.isArray(res.list)) list = res.list
    // 勾选态由前端维护：首次进入用后端回传的 selected 初始化，之后以本地为准
    // （后端没有修改勾选的接口，所以不能在每次 loadCart 时覆盖用户的勾选）
    cartStore.initFromList(list)
    items.value = list.map(i => ({ ...i, selected: cartStore.isSelected(i.productId) ? 1 : 0 }))
    console.log('[Cart] loaded', list.length, 'items', list)
  } catch (e) {
    console.error('[Cart] load error', e)
    items.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadCart)

const isAllSelected = computed(() =>
  items.value.length > 0 && items.value.every(i => Number(i.selected) === 1)
)
const isIndeterminate = computed(() => {
  const cnt = items.value.filter(i => Number(i.selected) === 1).length
  return cnt > 0 && cnt < items.value.length
})
const selectedCount = computed(() => items.value.filter(i => Number(i.selected) === 1).length)
const totalPrice = computed(() =>
  items.value
    .filter(i => Number(i.selected) === 1)
    .reduce((sum, i) => sum + Number(i.price ?? 0) * i.quantity, 0)
)

const handleSelectAll = (val) => {
  const selected = Boolean(val)
  items.value.forEach(item => { item.selected = selected ? 1 : 0 })
  cartStore.setAll(items.value.map(i => i.productId), selected)
}

const handleSelect = (item) => {
  const newVal = Number(item.selected) === 1 ? 0 : 1
  item.selected = newVal
  cartStore.setOne(item.productId, newVal === 1)
}

const handleQtyChange = async (item, qty) => {
  if (qty < 1) return
  await Cart.updateQty(item.productId, qty)
  item.quantity = qty
  cartStore.refresh()   // 数量变化不影响条目数，但保持角标与后端一致
}

const handleQtyInput = (item, event) => {
  let qty = parseInt(event.target.value, 10)
  if (Number.isNaN(qty) || qty < 1) qty = 1
  handleQtyChange(item, qty)
}

const handleRemove = async (item) => {
  try {
    await Cart.remove(item.productId)
    cartStore.remove(item.productId)
    cartStore.refresh()  // 同步顶栏购物车角标
    ElMessage.success('删除成功')
    loadCart()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

const goProduct = (id) => router.push(`/product/${id}`)

const goLogin = () => router.push({ path: '/login', query: { redirect: route.fullPath } })

const handleCheckout = () => {
  if (selectedCount.value === 0) {
    ElMessage.warning('请先勾选要结算的商品')
    return
  }
  router.push('/checkout')
}
</script>

<style scoped>
.cart-page { min-height: 60vh; }

.cart-table {
  width: 100%;
  background: #fff;
  border-radius: 10px;
  overflow: hidden;
}
.cart-table th,
.cart-table td { vertical-align: middle; padding: 14px 12px; border-bottom: 1px solid #f5f5f5; }
.cart-table thead th { background: #fafafa; color: #666; font-weight: 500; font-size: 13px; }
.cart-table tbody tr:hover { background: #fff9f5; }

.col-check { width: 90px; text-align: center; }
.col-product { width: 40%; }
.col-price { width: 110px; text-align: center; }
.col-qty { width: 150px; text-align: center; }
.col-subtotal { width: 130px; text-align: center; }
.col-action { width: 100px; text-align: center; }

.product-cell { display: flex; align-items: center; gap: 12px; cursor: pointer; }
.product-img { width: 72px; height: 72px; border-radius: 6px; object-fit: cover; flex-shrink: 0; }
.img-placeholder { width: 72px; height: 72px; border-radius: 6px; background: #f2f2f2; display: flex; align-items: center; justify-content: center; color: #bbb; font-size: 24px; flex-shrink: 0; }
.product-name { font-size: 14px; color: var(--text); line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }

.qty-stepper { display: inline-flex; align-items: center; border: 1px solid #e5e5e5; border-radius: 4px; overflow: hidden; }
.qty-stepper button { width: 28px; height: 30px; border: none; background: #fafafa; color: #666; cursor: pointer; font-size: 16px; }
.qty-stepper button:hover:not(:disabled) { background: #ff5000; color: #fff; }
.qty-stepper input { width: 50px; height: 30px; border: none; border-left: 1px solid #e5e5e5; border-right: 1px solid #e5e5e5; text-align: center; font-size: 14px; outline: none; }
.qty-stepper button:disabled { color: #ccc; cursor: not-allowed; }
.qty-stepper input::-webkit-outer-spin-button,
.qty-stepper input::-webkit-inner-spin-button { -webkit-appearance: none; }
.qty-stepper input[type='number'] { -moz-appearance: textfield; appearance: textfield; }

.price-display .symbol { font-size: 12px; color: #E1251B; margin-right: 1px; }
.price-display { color: #E1251B; font-weight: 600; }

.cart-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; background: #fff; border-radius: var(--radius); padding: 14px 20px; margin-top: 16px; box-shadow: var(--shadow); }
.footer-left { display: flex; align-items: center; gap: 16px; }
.footer-count { font-size: 13px; color: var(--text2); }
.footer-count b { color: var(--primary); }
.footer-right { display: flex; align-items: center; gap: 20px; }
.footer-total { font-size: 14px; color: var(--text); }
.footer-total .amount { font-size: 22px; color: #E1251B; font-weight: 700; }

.btn-primary { background: #ff5000; border-color: #ff5000; color: #fff; }
.btn-primary:hover { background: #e64600; border-color: #e64600; }
.checkout-btn { padding: 10px 30px; border-radius: 4px; border: none; cursor: pointer; font-size: 15px; }
.checkout-btn:disabled { background: #ccc; cursor: not-allowed; }

.empty-state { padding: 60px 0; text-align: center; color: #999; }
.empty-state p { margin: 0 0 16px; font-size: 14px; }

.loading-state { padding: 40px 0; text-align: center; color: #999; font-size: 14px; }
</style>
