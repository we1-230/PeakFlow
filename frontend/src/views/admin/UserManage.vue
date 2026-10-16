<template>
  <div class="page-card">
    <!-- 工具栏 -->
    <div class="toolbar">
      <el-input
        v-model="searchForm.username"
        placeholder="按用户名 / 昵称搜索"
        clearable
        style="width: 220px"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 140px">
        <el-option label="正常" :value="1" />
        <el-option label="已禁用" :value="0" />
      </el-select>
      <el-button type="primary" :loading="loading" @click="handleSearch">搜索</el-button>
      <el-button @click="handleReset">重置</el-button>
    </div>

    <!-- 用户表格 -->
    <el-table :data="list" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="用户" min-width="180">
        <template #default="{ row }">
          <div class="user-cell">
            <span class="user-avatar" :style="{ background: row.status === 1 ? 'linear-gradient(135deg,#ff5000,#ff7a1a)' : '#a2968b' }">
              {{ (row.nickname || row.username || '用').slice(0, 1) }}
            </span>
            <div class="user-cell-info">
              <span class="user-cell-name">{{ row.nickname || '-' }}</span>
              <span class="user-cell-username">@{{ row.username }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="phone" label="手机号" width="130">
        <template #default="{ row }">{{ row.phone || '-' }}</template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱" min-width="170" show-overflow-tooltip />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" effect="light" size="small">
            {{ row.status === 1 ? '正常' : '已禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最后登录" width="165">
        <template #default="{ row }">{{ row.lastLoginTime ? fmtTime(row.lastLoginTime) : '-' }}</template>
      </el-table-column>
      <el-table-column prop="createTime" label="注册时间" width="165" />
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 1"
            type="danger" size="small" plain @click="handleBan(row)"
          >封禁</el-button>
          <el-button
            v-else
            type="success" size="small" plain @click="handleUnban(row)"
          >解封</el-button>
          <el-button type="warning" size="small" plain @click="handleResetPwd(row)">重置密码</el-button>
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

    <!-- 重置密码结果 -->
    <el-dialog v-model="pwdDialogVisible" title="重置密码成功" width="440" :close-on-click-modal="false">
      <p style="margin: 0 0 10px; color: #5c524b;">
        用户 <b style="color:#2e2620;">{{ pwdTarget?.nickname || pwdTarget?.username }}</b> 的新密码为：
      </p>
      <div class="pwd-box">
        <span>{{ newPassword }}</span>
        <el-button size="small" type="primary" link @click="copyPassword">复制</el-button>
      </div>
      <p style="color: #a89a8f; font-size: 12px; margin: 10px 0 0;">
        请妥善保管并尽快告知该用户。该密码为临时密码，用户首次登录后会被要求修改。
      </p>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { Admin } from '@/api'

const loading = ref(false)
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

const searchForm = reactive({ username: '', status: undefined })

const pwdDialogVisible = ref(false)
const newPassword = ref('')
const pwdTarget = ref(null)

function fmtTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}

async function loadData() {
  loading.value = true
  try {
    const data = await Admin.list({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      username: searchForm.username || undefined,
      status: searchForm.status === '' || searchForm.status === undefined ? undefined : searchForm.status,
    })
    list.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    // 错误已由拦截器提示
  } finally {
    loading.value = false
  }
}

function handleSearch() { pageNum.value = 1; loadData() }
function handleReset() { searchForm.username = ''; searchForm.status = undefined; handleSearch() }
function handleSizeChange() { pageNum.value = 1; loadData() }

async function handleBan(row) {
  try {
    await ElMessageBox.confirm(
      `确定要封禁用户「${row.username}」吗？封禁后该用户将无法登录。`,
      '封禁确认',
      { type: 'warning', confirmButtonText: '封禁', cancelButtonText: '取消' }
    )
  } catch (e) { return }
  try {
    await Admin.ban(row.id)
    ElMessage.success('封禁成功')
    row.status = 0
  } catch (e) { /* 拦截器已提示 */ }
}

async function handleUnban(row) {
  try {
    await ElMessageBox.confirm(`确定要解封用户「${row.username}」吗？`, '解封确认', {
      type: 'info', confirmButtonText: '解封', cancelButtonText: '取消',
    })
  } catch (e) { return }
  try {
    await Admin.unban(row.id)
    ElMessage.success('解封成功')
    row.status = 1
  } catch (e) { /* 拦截器已提示 */ }
}

async function handleResetPwd(row) {
  try {
    const pwd = await Admin.resetPassword(row.id)
    newPassword.value = pwd || ''
    pwdTarget.value = row
    pwdDialogVisible.value = true
  } catch (e) { /* 拦截器已提示 */ }
}

async function copyPassword() {
  try {
    await navigator.clipboard.writeText(newPassword.value)
    ElMessage.success('已复制到剪贴板')
  } catch (e) {
    ElMessage.error('复制失败，请手动复制')
  }
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
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.user-cell { display: flex; align-items: center; gap: 10px; }
.user-avatar {
  width: 36px; height: 36px;
  border-radius: 50%;
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.user-cell-info { display: flex; flex-direction: column; line-height: 1.3; }
.user-cell-name { font-size: 14px; font-weight: 600; color: #2e2620; }
.user-cell-username { font-size: 12px; color: #a89a8f; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.pwd-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff7f2;
  border: 1px solid #ffeadb;
  border-radius: 8px;
  padding: 12px 16px;
  font-size: 16px;
  font-weight: 700;
  color: #ff5000;
  letter-spacing: 1px;
  word-break: break-all;
  margin-top: 4px;
}
</style>
