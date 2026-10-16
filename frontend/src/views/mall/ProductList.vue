<template>
  <div class="mall-main product-list-page">
    <!-- Search Bar -->
    <div class="search-bar">
      <el-input
        v-model="searchKeyword"
        placeholder="搜索商品..."
        size="large"
        clearable
        class="search-input"
        @keyup.enter="doSearch"
      >
        <template #append>
          <el-button type="primary" @click="doSearch">搜索</el-button>
        </template>
      </el-input>
    </div>

    <div class="content-layout">
      <!-- Category Sidebar -->
      <aside class="category-sidebar">
        <div class="sidebar-title">全部分类</div>
        <div
          class="category-item"
          :class="{ active: !activeCategoryId }"
          @click="selectCategory(null)"
        >
          全部商品
        </div>
        <div
          v-for="cat in categories"
          :key="cat.id"
          class="category-item"
          :class="{ active: activeCategoryId === cat.id }"
          @click="selectCategory(cat.id)"
        >
          <span class="cat-icon">
            <img
              v-if="cat.icon && !cat.iconError"
              :src="cat.icon"
              :alt="cat.name"
              class="cat-icon-img"
              @error="cat.iconError = true"
            />
            <span v-else>{{ cat.name.slice(0, 1) }}</span>
          </span>
          <span>{{ cat.name }}</span>
        </div>
      </aside>

      <!-- Product Results -->
      <div class="result-area">
        <div class="result-header">
          <span v-if="activeKeyword">关键词“{{ activeKeyword }}”的搜索结果</span>
          <span v-else-if="activeCategoryName">分类“{{ activeCategoryName }}”</span>
          <span>共 {{ total }} 件商品</span>
        </div>

        <el-skeleton v-if="loading" :rows="4" animated class="result-skeleton" />

        <template v-else>
          <div v-if="products.length" class="product-grid">
            <div
              v-for="p in products"
              :key="p.id"
              class="product-card"
              @click="goProduct(p.id)"
            >
              <div class="img-wrap">
                <img
                  v-if="p.image"
                  :src="p.image"
                  :alt="p.name"
                  @error="p.image = ''"
                />
                <div v-else class="img-placeholder">{{ p.name.slice(0, 1) }}</div>
                <span v-if="p.isSeckill" class="badge-seckill">秒杀</span>
              </div>
              <div class="info">
                <div class="title">{{ p.name }}</div>
                <div class="price-display">
                  <span class="symbol">¥</span>
                  <span class="amount">{{ Number(p.isSeckill && p.seckillPrice != null ? p.seckillPrice : p.price).toFixed(2) }}</span>
                  <span v-if="p.isSeckill && p.seckillPrice != null && p.price" class="price-old">¥{{ Number(p.price).toFixed(2) }}</span>
                </div>
                <div v-if="p.stock != null" class="stock-bar">
                  <div class="stock-fill" :style="{ width: stockPercent(p) + '%' }"></div>
                </div>
              </div>
            </div>
          </div>
          <el-empty v-else description="没有找到相关商品，换个关键词试试吧" class="empty-state" />
        </template>

        <div class="pagination-wrap">
          <el-pagination
            v-model:current-page="query.pageNum"
            :page-size="query.pageSize"
            :total="total"
            layout="prev, pager, next, jumper, total"
            background
            @current-change="loadProducts"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Product, Category } from '@/api'

const route = useRoute()
const router = useRouter()

const searchKeyword = ref('')
const categories = ref([])
const products = ref([])
const total = ref(0)
const loading = ref(false)

const query = reactive({
  pageNum: 1,
  pageSize: 20,
  categoryId: null,
  keyword: '',
})

const activeCategoryId = computed(() => query.categoryId)
const activeKeyword = computed(() => query.keyword)
const activeCategoryName = computed(() =>
  categories.value.find(c => c.id === query.categoryId)?.name || ''
)

function syncFromRoute() {
  const { categoryId, keyword, pageNum } = route.query
  query.categoryId = categoryId ? Number(categoryId) : null
  query.keyword = keyword || ''
  query.pageNum = pageNum ? Number(pageNum) : 1
  searchKeyword.value = query.keyword
}

function stockPercent(p) {
  const totalStock = (p.stock || 0) + (p.soldCount || 0)
  if (!totalStock) return 0
  return Math.min(100, Math.round(((p.soldCount || 0) / totalStock) * 100))
}

async function loadCategories() {
  try {
    const res = await Category.list()
    categories.value = (res || []).filter(c => c.status !== 0).map(c => ({ ...c, iconError: false }))
  } catch (e) {
    console.error(e)
  }
}

async function loadProducts() {
  loading.value = true
  try {
    const params = {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
    }
    if (query.categoryId) params.categoryId = query.categoryId
    if (query.keyword) params.keyword = query.keyword
    const res = await Product.page(params)
    products.value = res?.list || []
    total.value = res?.total || 0
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

function doSearch() {
  query.keyword = normalizeKeyword(searchKeyword.value)
  query.pageNum = 1
  updateRoute()
}

// 关键词规范化：全角空格→半角、连续空白→单空格、去首尾空白，
// 确保后端 keyword.replace(' ','%') 的多词模糊匹配能正确触发
function normalizeKeyword(kw) {
  return (kw || '').replace(/\u3000/g, ' ').replace(/[ \t]+/g, ' ').trim()
}

function selectCategory(categoryId) {
  query.categoryId = categoryId
  query.pageNum = 1
  updateRoute()
}

function updateRoute() {
  const q = {}
  if (query.categoryId) q.categoryId = query.categoryId
  if (query.keyword) q.keyword = query.keyword
  if (query.pageNum > 1) q.pageNum = query.pageNum
  router.replace({ path: '/products', query: q })
}

function goProduct(id) {
  router.push(`/product/${id}`)
}

watch(
  () => route.query,
  () => {
    syncFromRoute()
    loadProducts()
  }
)

onMounted(() => {
  syncFromRoute()
  loadCategories()
  loadProducts()
})
</script>

<style scoped>
.product-list-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.search-bar {
  background: #fff;
  border-radius: 10px;
  padding: 16px;
}

.search-input {
  max-width: 640px;
}

.content-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.category-sidebar {
  flex: 0 0 180px;
  width: 180px;
  background: #fff;
  border-radius: 10px;
  padding: 12px 0;
  position: sticky;
  top: 12px;
}

.sidebar-title {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  padding: 8px 16px 12px;
  border-bottom: 1px solid #f0f0f0;
}

.category-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  font-size: 14px;
  color: #555;
  cursor: pointer;
  transition: all 0.15s;
}

.category-item:hover {
  color: #ff5000;
  background: #fff7f2;
}

.category-item.active {
  color: #ff5000;
  font-weight: 600;
  background: #fff2e8;
  border-right: 3px solid #ff5000;
}

.cat-icon {
  width: 24px;
  height: 24px;
  border-radius: 4px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f5;
  font-size: 13px;
  color: #999;
  flex-shrink: 0;
}
.cat-icon-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.result-area {
  flex: 1;
  min-width: 0;
}

.result-header {
  background: #fff;
  border-radius: 10px;
  padding: 12px 16px;
  margin-bottom: 14px;
  font-size: 14px;
  color: #666;
  display: flex;
  justify-content: space-between;
}

.result-skeleton {
  background: #fff;
  border-radius: 10px;
  padding: 24px;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 14px;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  padding: 28px 0;
}
</style>
