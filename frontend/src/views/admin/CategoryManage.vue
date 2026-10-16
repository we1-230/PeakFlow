<template>
  <div class="page-card">
    <!-- 工具栏 -->
    <div class="toolbar">
      <span class="page-hint">共 {{ list.length }} 个分类（按排序值升序展示）</span>
      <div class="spacer"></div>
      <el-button type="primary" @click="openAdd"><el-icon style="margin-right:4px;"><Plus /></el-icon>新增分类</el-button>
    </div>

    <!-- 分类表格 -->
    <el-table :data="list" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="图标" width="90" align="center">
        <template #default="{ row }">
          <el-image
            v-if="row.icon"
            :src="row.icon"
            fit="cover"
            style="width: 42px; height: 42px; border-radius: 8px;"
            :preview-src-list="[row.icon]"
            preview-teleported
          />
          <span v-else class="no-icon">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="160">
        <template #default="{ row }">
          <span style="font-weight:600; color:#2e2620;">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="sort" label="排序值" width="110" align="center" />
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 1"
            :loading="row._statusLoading"
            @change="v => toggleStatus(row, v)"
            active-text="启用"
            inactive-text="停用"
            inline-prompt
          />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ row.createTime ? String(row.createTime).replace('T', ' ').slice(0, 16) : '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openEdit(row)">编辑</el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑分类' : '新增分类'" width="480" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入分类名称" maxlength="50" />
        </el-form-item>
        <el-form-item label="图标" prop="icon">
          <div class="upload-wrap">
            <el-upload :show-file-list="false" :before-upload="beforeUpload" :http-request="handleUpload" accept="image/*">
              <el-image
                v-if="form.icon"
                :src="form.icon"
                fit="cover"
                style="width: 64px; height: 64px; border-radius: 8px;"
                :preview-src-list="[form.icon]"
                preview-teleported
              />
              <div v-else class="upload-placeholder">
                <el-icon :size="22"><Plus /></el-icon>
                <span>点击上传</span>
              </div>
            </el-upload>
            <el-input v-model="form.icon" placeholder="或直接粘贴图片链接" />
          </div>
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" :step="1" style="width: 160px" />
          <span class="sort-tip">数值越小越靠前</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
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
import { Plus } from '@element-plus/icons-vue'
import { Category, Upload } from '@/api'

const loading = ref(false)
const saving = ref(false)
const list = ref([])

const dialogVisible = ref(false)
const formRef = ref()
const form = reactive({ id: undefined, name: '', icon: '', sort: 0, status: 1 })

const rules = {
  name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }],
}

async function loadData() {
  loading.value = true
  try {
    list.value = (await Category.list()) || []
  } catch (e) {
  } finally {
    loading.value = false
  }
}

function resetForm() {
  Object.assign(form, { id: undefined, name: '', icon: '', sort: 0, status: 1 })
}

function openAdd() { resetForm(); dialogVisible.value = true }

function openEdit(row) {
  Object.assign(form, {
    id: row.id, name: row.name, icon: row.icon || '',
    sort: row.sort ?? 0, status: row.status === 1 ? 1 : 0,
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
      await Category.update(payload)
      ElMessage.success('更新成功')
    } else {
      await Category.add(payload)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (e) {
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row, v) {
  const newStatus = v ? 1 : 0
  row._statusLoading = true
  try {
    await Category.update({
      id: row.id, name: row.name, icon: row.icon || '',
      sort: row.sort ?? 0, status: newStatus,
    })
    row.status = newStatus
    ElMessage.success(newStatus === 1 ? '分类已启用' : '分类已停用')
  } catch (e) {
  } finally {
    row._statusLoading = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除分类「${row.name}」吗？删除后其下商品将无法正常归属。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) { return }
  try {
    await Category.remove(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {}
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
    form.icon = url
    ElMessage.success('图片上传成功')
  } catch (e) {}
}

onMounted(loadData)
</script>

<style scoped>
.page-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  border: 1px solid #f2ece7;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.05);
}
.toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 18px; }
.page-hint { font-size: 13px; color: #a89a8f; }
.spacer { flex: 1; }
.no-icon { color: #ccc; }
.upload-wrap { display: flex; gap: 12px; align-items: center; width: 100%; }
.upload-placeholder {
  width: 64px; height: 64px;
  border: 1px dashed #dcdfe6;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #a89a8f;
  font-size: 12px;
  gap: 4px;
  cursor: pointer;
  transition: border-color .2s, color .2s;
  flex-shrink: 0;
}
.upload-placeholder:hover { border-color: #ff5000; color: #ff5000; }
.sort-tip { font-size: 12px; color: #a89a8f; margin-left: 10px; }
</style>
