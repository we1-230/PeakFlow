<template>
  <div class="page-card">
    <!-- 工具栏 -->
    <div class="toolbar">
      <el-input
        v-model="searchForm.keyword"
        placeholder="按商品名称搜索"
        clearable
        style="width: 220px"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="searchForm.categoryId" placeholder="全部分类" clearable style="width: 160px">
        <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-button type="primary" :loading="loading" @click="handleSearch">搜索</el-button>
      <el-button @click="handleReset">重置</el-button>
      <div class="spacer"></div>
      <el-button type="primary" @click="openAdd"><el-icon style="margin-right:4px;"><Plus /></el-icon>新增商品</el-button>
    </div>

    <!-- 商品表格 -->
    <el-table :data="list" v-loading="loading" stripe>
      <el-table-column label="商品" min-width="260">
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
      <el-table-column label="价格" width="110" align="right">
        <template #default="{ row }"><span class="price-text">¥{{ money(row.price) }}</span></template>
      </el-table-column>
      <el-table-column prop="stock" label="库存" width="80" align="center" />
      <el-table-column label="分类" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ categoryName(row.categoryId) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '上架' : '下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上架开关" width="100" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 1"
            :loading="row._statusLoading"
            @change="v => toggleStatus(row, v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
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
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑商品' : '新增商品'" width="640" :close-on-click-modal="false">
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
          <el-form-item label="价格" prop="price">
            <el-input-number v-model="form.price" :min="0" :precision="2" :step="1" style="width: 170px" />
          </el-form-item>
          <el-form-item label="库存" prop="stock">
            <el-input-number v-model="form.stock" :min="0" :step="1" style="width: 170px" />
          </el-form-item>
          <el-form-item label="分类" prop="categoryId">
            <el-select v-model="form.categoryId" placeholder="请选择" style="width: 150px">
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="上架状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="上架" inactive-text="下架" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="商品简短描述（200字内）" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="详情" prop="detail">
          <el-input v-model="form.detail" type="textarea" :rows="4" placeholder="商品详细说明（2000字内）" maxlength="2000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { Product, Category, Upload } from '@/api'

const loading = ref(false)
const saving = ref(false)
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const categories = ref([])

const searchForm = reactive({ keyword: '', categoryId: undefined })

const dialogVisible = ref(false)
const formRef = ref()
const form = reactive({
  id: undefined, name: '', description: '', detail: '', image: '',
  price: 0, stock: 0, categoryId: undefined, status: 1,
})

const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
}

function money(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '0.00'
}
function categoryName(id) {
  return categories.value.find(c => c.id === id)?.name || '-'
}

async function loadCategories() {
  try { categories.value = (await Category.list()) || [] } catch (e) {}
}

async function loadData() {
  loading.value = true
  try {
    const data = await Product.page({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: searchForm.keyword || undefined,
      categoryId: searchForm.categoryId === '' || searchForm.categoryId === undefined ? undefined : searchForm.categoryId,
    })
    list.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
  } finally {
    loading.value = false
  }
}

function handleSearch() { pageNum.value = 1; loadData() }
function handleReset() { searchForm.keyword = ''; searchForm.categoryId = undefined; handleSearch() }
function handleSizeChange() { pageNum.value = 1; loadData() }

function resetForm() {
  Object.assign(form, { id: undefined, name: '', description: '', detail: '', image: '', price: 0, stock: 0, categoryId: undefined, status: 1 })
}

function openAdd() { resetForm(); dialogVisible.value = true }

function openEdit(row) {
  Object.assign(form, {
    id: row.id, name: row.name, description: row.description || '', detail: row.detail || '',
    image: row.image || '', price: row.price, stock: row.stock,
    categoryId: row.categoryId, status: row.status === 1 ? 1 : 0,
  })
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  try { await formRef.value.validate() } catch (e) { return }
  saving.value = true
  try {
    const payload = { ...form }
    if (form.id) {
      await Product.update(payload)
      ElMessage.success('更新成功')
    } else {
      await Product.add(payload)
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

async function toggleStatus(row, v) {
  const newStatus = v ? 1 : 0
  row._statusLoading = true
  try {
    await Product.status(row.id, newStatus)
    row.status = newStatus
    ElMessage.success(newStatus === 1 ? '商品已上架' : '商品已下架')
  } catch (e) {
  } finally {
    row._statusLoading = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定要删除商品「${row.name}」吗？此操作不可恢复。`, '删除确认', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch (e) { return }
  try {
    await Product.remove(row.id)
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
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
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
.price-text { color: #E1251B; font-weight: 700; }
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
</style>
