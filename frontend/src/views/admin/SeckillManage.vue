<template>
  <div class="page-card">
    <!-- 工具栏 -->
    <div class="toolbar">
      <el-button :loading="preheating" @click="handlePreheat">
        <el-icon style="margin-right:4px;"><Lightning /></el-icon>缓存预热
      </el-button>
      <span class="page-hint">秒杀商品独立管理 · 与普通商品分库隔离</span>
      <div class="spacer"></div>
      <el-button type="danger" plain @click="openAdd"><el-icon style="margin-right:4px;"><Plus /></el-icon>新增秒杀商品</el-button>
    </div>

    <!-- 秒杀商品表格 -->
    <el-table :data="list" v-loading="loading" stripe>
      <el-table-column label="商品" min-width="240">
        <template #default="{ row }">
          <div class="product-cell">
            <el-image
              v-if="row.image"
              :src="row.image"
              fit="cover"
              class="product-img"
              :preview-src-list="[row.image]"
              preview-teleported
            />
            <span v-else class="product-img product-img--empty">无图</span>
            <div class="product-cell-info">
              <span class="product-name">{{ row.name }}</span>
              <span class="product-id">ID: {{ row.id }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="原价" width="100" align="right">
        <template #default="{ row }"><span class="old-price">¥{{ money(row.price) }}</span></template>
      </el-table-column>
      <el-table-column label="秒杀价" width="110" align="right">
        <template #default="{ row }"><span class="seckill-price">¥{{ money(row.seckillPrice) }}</span></template>
      </el-table-column>
      <el-table-column label="秒杀库存" width="110" align="center">
        <template #default="{ row }">
          <span :class="{ 'stock-low': row.seckillStock < 50 }">{{ row.seckillStock }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="soldCount" label="已售" width="80" align="center" />
      <el-table-column label="秒杀时间窗" min-width="200">
        <template #default="{ row }">
          <div class="time-window">
            <span>{{ fmtTime(row.seckillStartTime) }}</span>
            <span class="time-sep">→</span>
            <span>{{ fmtTime(row.seckillEndTime) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <!-- 秒杀商品没有独立的上下架接口（/product/status 会拒绝秒杀商品），
               上下架只能由活动时间窗决定，因此这里只做展示 -->
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '上架' : '下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button type="warning" link size="small" @click="openStock(row)">调库存</el-button>
          <el-button type="primary" link size="small" @click="openEdit(row)">编辑</el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pager">
      <el-pagination
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="loadData"
        @size-change="handleSizeChange"
      />
    </div>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑秒杀商品' : '新增秒杀商品'" width="640" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入商品名称" maxlength="30" />
        </el-form-item>
        <el-form-item label="商品图片" prop="image">
          <div class="upload-wrap">
            <el-upload :show-file-list="false" :before-upload="beforeUpload" :http-request="handleUpload" accept="image/*">
              <el-image
                v-if="form.image"
                :src="form.image"
                fit="cover"
                style="width: 120px; height: 120px; border-radius: 8px;"
                :preview-src-list="[form.image]"
                preview-teleported
              />
              <div v-else class="upload-placeholder">
                <el-icon :size="32"><Plus /></el-icon>
                <span>点击上传</span>
              </div>
            </el-upload>
            <el-input v-model="form.image" placeholder="或直接粘贴图片链接" style="flex:1" />
          </div>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="原价" prop="price">
            <el-input-number v-model="form.price" :min="0" :precision="2" :step="1" style="width: 160px" />
          </el-form-item>
          <el-form-item label="秒杀价" prop="seckillPrice">
            <el-input-number v-model="form.seckillPrice" :min="0" :precision="2" :step="1" style="width: 160px" />
          </el-form-item>
          <el-form-item label="分类" prop="categoryId">
            <el-select v-model="form.categoryId" placeholder="请选择" style="width: 140px">
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="秒杀库存" prop="seckillStock">
          <el-input-number v-model="form.seckillStock" :min="0" :step="1" style="width: 160px" />
          <span class="sort-tip">秒杀专属库存，与普通库存独立</span>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="开始时间" prop="seckillStartTime">
            <el-date-picker
              v-model="form.seckillStartTime"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              placeholder="选择开始时间"
              style="width: 200px"
            />
          </el-form-item>
          <el-form-item label="结束时间" prop="seckillEndTime">
            <el-date-picker
              v-model="form.seckillEndTime"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              placeholder="选择结束时间"
              style="width: 200px"
            />
          </el-form-item>
        </div>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 18px"
          title="秒杀商品没有独立的上下架接口：上下架由活动时间窗决定，且活动进行中的商品不允许修改或删除"
        />
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="秒杀商品描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 调整库存弹窗 -->
    <el-dialog v-model="stockDialogVisible" title="调整秒杀库存" width="420" :close-on-click-modal="false">
      <p style="margin: 0 0 14px; color: #5c524b;">
        商品 <b style="color:#2e2620;">{{ stockForm.name }}</b> 当前秒杀库存：
        <b style="color: #E1251B;">{{ stockForm.seckillStock }}</b>
      </p>
      <el-form label-width="90px">
        <el-form-item label="调整为">
          <el-input-number v-model="stockForm.newStock" :min="0" :step="10" style="width: 180px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stockDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="stockSaving" @click="handleSaveStock">确认调整</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Lightning } from '@element-plus/icons-vue'
import { Seckill, Upload, Category } from '@/api'

const loading = ref(false)
const saving = ref(false)
const preheating = ref(false)
const stockSaving = ref(false)
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const categories = ref([])

const dialogVisible = ref(false)
const formRef = ref()
const form = reactive({
  id: undefined, name: '', description: '', image: '',
  price: 0, seckillPrice: 0, stock: 0, seckillStock: 0,
  categoryId: undefined, status: 1,
})

const stockDialogVisible = ref(false)
const stockForm = reactive({ id: undefined, name: '', seckillStock: 0, newStock: 0 })

const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
  seckillPrice: [{ required: true, message: '请输入秒杀价', trigger: 'blur' }],
  seckillStock: [{ required: true, message: '请输入秒杀库存', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  // 新增/更新秒杀商品时，后端 AddSeckillProductDto / UpdateSeckillProductDto 都强制要求时间窗
  seckillStartTime: [{ required: true, message: '请选择秒杀开始时间', trigger: 'change' }],
  seckillEndTime: [{ required: true, message: '请选择秒杀结束时间', trigger: 'change' }],
}

function money(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '0.00'
}
function fmtTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}
// 后端 LocalDateTime 要求 yyyy-MM-dd HH:mm:ss；列表返回的可能是 ISO（带 T），回填表单时统一格式
function toApiTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}

async function loadCategories() {
  try { categories.value = (await Category.list()) || [] } catch (e) {}
}

async function loadData() {
  loading.value = true
  try {
    const data = await Seckill.list({ pageNum: pageNum.value, pageSize: pageSize.value })
    list.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
  } finally {
    loading.value = false
  }
}

function handleSizeChange() { pageNum.value = 1; loadData() }

async function handlePreheat() {
  try {
    await ElMessageBox.confirm('将对所有秒杀商品进行缓存预热，请确保秒杀时间窗配置正确。是否继续？', '缓存预热', {
      type: 'info', confirmButtonText: '立即预热', cancelButtonText: '取消',
    })
  } catch (e) { return }
  preheating.value = true
  try {
    await Seckill.preheatAll()
    ElMessage.success('缓存预热完成')
  } catch (e) {
  } finally {
    preheating.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    id: undefined, name: '', description: '', image: '',
    price: 0, seckillPrice: 0, stock: 0, seckillStock: 0,
    categoryId: undefined, status: 1,
    seckillStartTime: '', seckillEndTime: '',
  })
}

function openAdd() { resetForm(); dialogVisible.value = true }

function openEdit(row) {
  Object.assign(form, {
    id: row.id, name: row.name, description: row.description || '',
    image: row.image || '', price: row.price,
    seckillPrice: row.seckillPrice, stock: row.stock,
    seckillStock: row.seckillStock, categoryId: row.categoryId,
    status: row.status === 1 ? 1 : 0,
    seckillStartTime: toApiTime(row.seckillStartTime),
    seckillEndTime: toApiTime(row.seckillEndTime),
  })
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  try { await formRef.value.validate() } catch (e) { return }
  saving.value = true
  try {
    if (form.id) {
      // 更新：UpdateSeckillProductDto 必填 name/image/price/seckillPrice/categoryId/时间窗
      await Seckill.update({
        id: form.id, name: form.name, description: form.description,
        image: form.image, price: form.price, seckillPrice: form.seckillPrice,
        seckillStock: form.seckillStock, categoryId: form.categoryId,
        seckillStartTime: form.seckillStartTime, seckillEndTime: form.seckillEndTime,
      })
      ElMessage.success('更新成功')
    } else {
      // 新增：AddSeckillProductDto 字段名与更新一致（seckillPrice / seckillStock / 时间窗）
      await Seckill.add({
        name: form.name, description: form.description, image: form.image,
        price: form.price, seckillPrice: form.seckillPrice,
        seckillStock: form.seckillStock, categoryId: form.categoryId,
        seckillStartTime: form.seckillStartTime, seckillEndTime: form.seckillEndTime,
      })
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (e) {
  } finally {
    saving.value = false
  }
}

function beforeUpload(file) {
  const isImage = file.type.startsWith('image/')
  if (!isImage) { ElMessage.error('只能上传图片文件'); return false }
  if (file.size / 1024 / 1024 > 2) { ElMessage.error('图片大小不能超过 2MB'); return false }
  return true
}

async function handleUpload({ file }) {
  try {
    const url = await Upload.image(file)
    form.image = url
    ElMessage.success('图片上传成功')
  } catch (e) {}
}

function openStock(row) {
  Object.assign(stockForm, {
    id: row.id, name: row.name,
    seckillStock: row.seckillStock, newStock: row.seckillStock,
    row, // 保留整行，用于拼全量更新报文
  })
  stockDialogVisible.value = true
}

async function handleSaveStock() {
  stockSaving.value = true
  try {
    const row = stockForm.row || {}
    // 后端没有「单独改秒杀库存」的接口，只有 /seckill/update 全量更新，
    // 所以这里带上原有字段做一次全量更新，只把 seckillStock 换成新值。
    // （若活动正在进行中，后端会以「活动期间不允许修改」拒绝——这是业务规则）
    await Seckill.update({
      id: row.id, name: row.name, description: row.description,
      image: row.image, price: row.price, seckillPrice: row.seckillPrice,
      seckillStock: stockForm.newStock, categoryId: row.categoryId,
      seckillStartTime: toApiTime(row.seckillStartTime),
      seckillEndTime: toApiTime(row.seckillEndTime),
    })
    ElMessage.success('库存调整成功')
    stockDialogVisible.value = false
    loadData()
  } catch (e) {
  } finally {
    stockSaving.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定要删除秒杀商品「${row.name}」吗？此操作不可恢复。`, '删除确认', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch (e) { return }
  try {
    await Seckill.remove(row.id)
    ElMessage.success('删除成功')
    if (list.value.length === 1 && pageNum.value > 1) pageNum.value -= 1
    loadData()
  } catch (e) {}
}

onMounted(() => { loadCategories(); loadData() })
</script>

<style scoped>
.page-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  border: 1px solid #f2ece7;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.05);
}
.toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 18px; flex-wrap: wrap; }
.page-hint { font-size: 13px; color: #a89a8f; }
.spacer { flex: 1; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.product-cell { display: flex; align-items: center; gap: 12px; }
.product-img {
  width: 56px; height: 56px;
  border-radius: 8px;
  flex-shrink: 0;
  object-fit: cover;
  background: #f5f7fa;
}
.product-img--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: #a89a8f;
}
.product-cell-info { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.product-name { font-size: 14px; font-weight: 600; color: #2e2620; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.product-id { font-size: 12px; color: #a89a8f; }
.old-price { color: #a89a8f; text-decoration: line-through; font-size: 13px; }
.seckill-price { color: #E1251B; font-weight: 700; font-size: 15px; }
.stock-low { color: #E1251B; font-weight: 700; }
.time-window { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #5c524b; }
.time-sep { color: #c9c0b8; }
.upload-wrap { display: flex; gap: 12px; align-items: flex-start; width: 100%; }
.upload-placeholder {
  width: 120px; height: 120px;
  border: 1px dashed #dcdfe6;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #a89a8f;
  font-size: 13px;
  gap: 6px;
  cursor: pointer;
  transition: border-color .2s, color .2s;
  flex-shrink: 0;
}
.upload-placeholder:hover { border-color: #ff5000; color: #ff5000; }
.form-row { display: flex; gap: 12px; flex-wrap: wrap; }
.sort-tip { font-size: 12px; color: #a89a8f; margin-left: 10px; }
</style>
