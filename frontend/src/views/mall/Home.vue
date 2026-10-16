<template>
  <div class="mall-home">
    <!-- 轮播上方信息行：首页 / 秒杀专区 -->
    <div class="hero-meta">
      <span class="hero-crumb">首页 / 秒杀专区</span>
    </div>

    <!-- 轮播大屏 -->
    <el-carousel height="400px" :interval="4000" arrow="hover" class="hero-carousel">
      <el-carousel-item v-for="(slide, idx) in carouselSlides" :key="idx">
        <div class="carousel-item" :style="{ background: slide.bg }">
          <div class="carousel-content">
            <h2 class="carousel-title">{{ slide.title }}</h2>
            <p class="carousel-sub">{{ slide.sub }}</p>
            <button class="carousel-btn" @click="slide.action === 'seckill' ? scrollToSeckill() : goCategory()">{{ slide.btn }}</button>
          </div>
          <img v-if="slide.img" :src="slide.img" class="carousel-img" :alt="slide.title" @error="$event.target.style.display='none'" />
        </div>
      </el-carousel-item>
    </el-carousel>

    <div class="mall-main">
    <!-- Category Quick Nav -->
      <div class="category-nav category-nav-text">
        <div
          v-for="cat in categories"
          :key="cat.id"
          class="category-tag"
          @click="goCategory(cat.id)"
        >
          {{ cat.name }}
        </div>
      </div>

      <!-- Seckill Section -->
      <div ref="seckillSection" class="seckill-section">
        <div class="seckill-banner">
          <div class="seckill-banner-left">
            <span class="seckill-banner-title">⚡ 限时秒杀</span>
          </div>
          <span class="seckill-banner-more" @click="goSeckill()">更多秒杀 &gt;</span>
        </div>
        <div class="seckill-scroll">
          <div
            v-for="p in seckillProducts"
            :key="p.id"
            class="product-card seckill-card"
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
                <span class="amount">{{ (p.seckillPrice ?? p.price).toFixed(2) }}</span>
                <span v-if="p.price" class="price-old">¥{{ Number(p.price).toFixed(2) }}</span>
              </div>
            </div>
          </div>
          <el-empty v-if="!loadingSeckill && !seckillProducts.length" description="暂无秒杀商品" :image-size="60" />
        </div>
      </div>

      <!-- Product Grid -->
      <div class="section-title">全部商品</div>
      <div v-loading="loadingProducts" class="product-area">
        <el-skeleton v-if="loadingProducts" :rows="3" animated class="grid-skeleton" />
        <div v-else class="product-grid">
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
            </div>
          </div>
        </div>
        <el-empty v-if="!loadingProducts && !products.length" description="暂无商品" class="empty-state" />
        <div class="pagination-wrap">
          <div class="custom-pagination">
            <button class="page-btn" :disabled="query.pageNum <= 1" @click="goPage(query.pageNum - 1)">
              <span>上一页</span>
            </button>
            <div class="page-numbers">
              <button
                v-for="p in displayPages"
                :key="p"
                class="page-num"
                :class="{ active: p === query.pageNum, ellipsis: p === -1 }"
                @click="p > 0 && goPage(p)"
              >
                {{ p === -1 ? '...' : p }}
              </button>
            </div>
            <button class="page-btn" :disabled="query.pageNum >= totalPages" @click="goPage(query.pageNum + 1)">
              <span>下一页</span>
            </button>
            <span class="page-info">共 {{ total }} 件</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Product, Seckill, Category } from '@/api'
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore()

const router = useRouter()

const categories = ref([])
const seckillProducts = ref([])
const products = ref([])
const total = ref(0)
const loadingSeckill = ref(false)
const loadingProducts = ref(false)
const seckillSection = ref(null)

const query = reactive({
  pageNum: 1,
  pageSize: 20,
})

// 轮播大屏数据 — 每张图和主题强相关
const carouselSlides = [
  {
    title: '限时秒杀 火热进行中',
    sub: '精选好物 · 超低折扣 · 手慢无',
    btn: '立即抢购',
    action: 'seckill',
    bg: 'linear-gradient(135deg, #C81E1E 0%, #E1251B 50%, #F04E3E 100%)',
    img: 'https://images.unsplash.com/photo-1607082348824-0a96f2a4b9da?w=800&h=400&fit=crop&q=80',
  },
  {
    title: '品质好物 全场包邮',
    sub: '甄选品牌 · 正品保障 · 极速发货',
    btn: '逛逛全部分类',
    action: 'category',
    bg: 'linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #2b1810 100%)',
    img: 'https://images.unsplash.com/photo-1556742502-ec7c0e9f34b1?w=800&h=400&fit=crop&q=80',
  },
]

// 分页计算
const totalPages = computed(() => Math.ceil(total.value / query.pageSize) || 1)
const displayPages = computed(() => {
  const cur = query.pageNum
  const tp = totalPages.value
  if (tp <= 7) return Array.from({ length: tp }, (_, i) => i + 1)
  if (cur <= 4) return [1, 2, 3, 4, 5, -1, tp]
  if (cur >= tp - 3) return [1, -1, tp - 4, tp - 3, tp - 2, tp - 1, tp]
  return [1, -1, cur - 1, cur, cur + 1, -1, tp]
})
function goPage(p) {
  if (p < 1 || p > totalPages.value || p === query.pageNum) return
  query.pageNum = p
  loadProducts()
}

async function loadCategories() {
  try {
    const res = await Category.list()
    categories.value = (res || []).filter(c => c.status !== 0).map(c => ({ ...c, iconError: false }))
  } catch (e) {
    console.error(e)
  }
}

async function loadSeckill() {
  loadingSeckill.value = true
  try {
    const res = await Seckill.list({ pageNum: 1, pageSize: 12 })
    seckillProducts.value = res?.list || []
  } catch (e) {
    console.error(e)
  } finally {
    loadingSeckill.value = false
  }
}

async function loadProducts() {
  loadingProducts.value = true
  try {
    const res = await Product.page({ ...query })
    products.value = res?.list || []
    total.value = res?.total || 0
  } catch (e) {
    console.error(e)
  } finally {
    loadingProducts.value = false
  }
}

function goProduct(id) {
  router.push(`/product/${id}`)
}

function goCategory(categoryId) {
  router.push({ path: '/products', query: categoryId ? { categoryId } : {} })
}
function goSeckill() {
  router.push('/seckill')
}

function scrollToSeckill() {
  seckillSection.value?.scrollIntoView({ behavior: 'smooth' })
}

onMounted(() => {
  loadCategories()
  loadSeckill()
  loadProducts()
})
</script>

<style scoped>
.mall-home {
  min-height: 100vh;
  background: #f5f7fb;
}

/* 轮播上方信息行：首页 / 秒杀专区 */
.hero-meta {
  max-width: var(--max-w);
  margin: 0 auto;
  padding: 14px 10px 8px;
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 13px;
}
.hero-crumb { color: #9a948c; }

/* 轮播大屏（深空科技：网格纹理 + 光晕） */
.hero-carousel { width: 100%; }
.hero-carousel :deep(.el-carousel__indicators) { bottom: 16px; }
.hero-carousel :deep(.el-carousel__indicator--horizontal .el-carousel__button) {
  width: 28px; height: 4px; border-radius: 2px; background: rgba(255,255,255,0.5);
}
.hero-carousel :deep(.is-active .el-carousel__button) { background: #fff; width: 36px; box-shadow:0 0 10px rgba(255,255,255,.85); }
.carousel-item {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 0 80px;
  position: relative;
  overflow: hidden;
}
/* 网格纹理 + 光斑（科技氛围，不遮挡商品） */
.carousel-item::before {
  content: '';
  position: absolute; inset: 0;
  background-image:
    linear-gradient(rgba(255,255,255,.07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255,255,255,.07) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(ellipse at 20% 50%, black 5%, transparent 70%);
  pointer-events: none;
}
.carousel-item::after {
  content: '';
  position: absolute; inset: 0;
  background: radial-gradient(circle at 78% 30%, rgba(255,193,128,.2), transparent 45%);
  pointer-events: none;
}
.carousel-content { z-index: 2; max-width: 480px; }

.carousel-title { font-size: 38px; font-weight: 800; color: #fff; margin: 0 0 12px; letter-spacing: 2px; text-shadow: 0 2px 8px rgba(0,0,0,0.3), 0 0 30px rgba(255,193,128,.3); }
.carousel-sub { font-size: 16px; color: rgba(255,255,255,0.9); margin: 0 0 24px; }
.carousel-btn {
  background: linear-gradient(135deg, rgba(255,255,255,.98), rgba(237,243,252,.98));
  color: #E1251B;
  border: none;
  padding: 13px 38px;
  border-radius: 26px;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.25s;
  box-shadow: 0 4px 18px rgba(0,0,0,0.2);
}
.carousel-btn:hover { transform: translateY(-2px) scale(1.02); box-shadow: 0 8px 28px rgba(255,80,0,.35); }
.carousel-img {
  position: absolute;
  right: 60px;
  top: 50%;
  transform: translateY(-50%);
  max-height: 280px;
  max-width: 350px;
  border-radius: 14px;
  object-fit: cover;
  box-shadow: 0 10px 40px rgba(0,0,0,0.3), 0 0 40px rgba(255,138,61,.25);
  z-index: 1;
}

/* 分类导航（玻璃胶囊 + 发光悬停） */
.category-nav-text {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 28px;
}
.category-tag {
  padding: 9px 22px;
  background: rgba(255,255,255,.85);
  backdrop-filter: blur(6px);
  border-radius: 20px;
  font-size: 14px;
  color: #444;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid rgba(235,238,244,.9);
  white-space: nowrap;
  box-shadow: 0 2px 8px rgba(15,23,42,.05);
}
.category-tag:hover {
  background: linear-gradient(135deg,#fff2e8,#fff);
  color: #ff5000;
  border-color: rgba(255,80,0,.4);
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(255,80,0,.18);
}

/* 自定义分页 */
.pagination-wrap { display: flex; justify-content: center; padding: 32px 0 20px; }
.custom-pagination {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #fff;
  padding: 8px 12px;
  border-radius: 24px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
}
.page-btn {
  padding: 8px 18px;
  border-radius: 18px;
  border: none;
  background: #f5f5f5;
  color: #666;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}
.page-btn:hover:not(:disabled) { background: #ff5000; color: #fff; }
.page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.page-numbers { display: flex; gap: 4px; }
.page-num {
  min-width: 34px;
  height: 34px;
  border-radius: 17px;
  border: none;
  background: transparent;
  color: #666;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}
.page-num:hover:not(.ellipsis) { background: #fff2e8; color: #ff5000; }
.page-num.active { background: #ff5000; color: #fff; font-weight: 700; }
.page-num.ellipsis { cursor: default; color: #ccc; }
.page-info { font-size: 13px; color: #999; margin-left: 8px; white-space: nowrap; }

/* Seckill */
.seckill-section {
  margin-bottom: 32px;
}

.seckill-banner {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(120deg, #C81E1E 0%, #E1251B 60%, #F04E3E 100%);
  color: #fff;
  border-radius: 12px 12px 0 0;
  padding: 12px 20px;
  overflow: hidden;
}
.seckill-banner::before {
  content: '';
  position: absolute; inset: 0;
  background: linear-gradient(110deg, transparent 30%, rgba(255,255,255,.25) 46%, transparent 60%);
  transform: translateX(-100%);
  animation: seckillShine 3.5s ease-in-out infinite;
  pointer-events: none;
}
@keyframes seckillShine { 55%,100% { transform: translateX(100%); } }

.seckill-banner-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.seckill-banner-title {
  font-size: 20px;
  font-weight: 700;
}

.seckill-banner-more {
  cursor: pointer;
  font-size: 13px;
  opacity: 0.9;
}

.seckill-banner-more:hover {
  text-decoration: underline;
}

.seckill-scroll {
  display: flex;
  gap: 12px;
  overflow-x: auto;
  background: #fff;
  padding: 14px;
  border-radius: 0 0 10px 10px;
}

.seckill-card {
  flex: 0 0 160px;
  width: 160px;
}

/* Product Grid */
.product-area {
  min-height: 300px;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 14px;
}

.grid-skeleton {
  padding: 24px;
  background: #fff;
  border-radius: 10px;
}
</style>
