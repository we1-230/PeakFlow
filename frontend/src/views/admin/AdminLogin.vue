<template>
  <div class="admin-login-page">
    <!-- 左侧品牌区 -->
    <div class="login-left">
      <div class="left-inner">
        <div class="left-logo">
          <BrandMark :size="42" deep />
          <div class="left-brand-text">
            <span class="left-brand-name">全智能秒杀商城</span>
            <span class="left-brand-sub">ADMIN CONSOLE</span>
          </div>
        </div>
        <h1 class="left-title">管理后台</h1>
        <p class="left-desc">用户 / 商品 / 分类 / 秒杀 / 订单，一站式运营管理<br/>数据实时对接，安全可靠</p>
        <ul class="left-features">
          <li><i class="feat-dot"></i>实时业务数据统计与趋势分析</li>
          <li><i class="feat-dot"></i>秒杀商品独立管理与库存调整</li>
          <li><i class="feat-dot"></i>订单发货 / 状态流转全流程管控</li>
        </ul>
      </div>
      <div class="left-footer">© 2026 全智能秒杀商城 · 管理控制台</div>
    </div>

    <!-- 右侧登录区 -->
    <div class="login-right">
      <div class="login-card">
        <div class="card-badge">ADMIN</div>
        <div class="card-title">欢迎回来</div>
        <div class="card-sub">请输入管理员账号登录后台</div>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
          <el-form-item prop="account">
            <el-input v-model="form.account" placeholder="管理员账号" clearable>
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="管理员密码" show-password>
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="captchaVisible" prop="captchaCode">
            <div class="captcha-row">
              <el-input v-model="form.captchaCode" placeholder="请输入图形验证码" maxlength="4" clearable />
              <img v-if="captchaImg" :src="captchaImg" class="captcha-img" title="看不清？点击刷新" @click="loadCaptcha" />
            </div>
          </el-form-item>
          <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">
            登 录
          </el-button>
        </el-form>

        <div class="login-footer">
          <router-link to="/">← 返回商城首页</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { Auth } from '@/api'
import BrandMark from '@/components/BrandMark.vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ account: '', password: '', captchaCode: '' })
const rules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }],
}

// 图形验证码：登录失败次数达阈值后后端要求带验证码，此时展示输入框（点击图片刷新）
const captchaVisible = ref(false)
const captchaImg = ref('')
const captchaUuid = ref('')

async function loadCaptcha() {
  try {
    const data = await Auth.captcha()
    captchaUuid.value = data?.captchaUuid || ''
    captchaImg.value = data?.imageBase64 || ''
    captchaVisible.value = true
  } catch (e) {
    // 拿不到验证码时不影响其它错误提示
  }
}

async function handleLogin() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  loading.value = true
  try {
    const captcha = captchaVisible.value
      ? { captchaUuid: captchaUuid.value, captchaCode: form.captchaCode }
      : null
    await auth.login(form.account, form.password, captcha)
    if (!auth.isAdmin) {
      auth.logout()
      ElMessage.error('该账号无管理员权限')
      return
    }
    ElMessage.success('登录成功')
    router.push('/admin')
  } catch (e) {
    // 错误提示已由请求拦截器统一处理；这里额外判断是否需要补图形验证码
    if (/验证码/.test(e.message || '')) {
      captchaVisible.value = true
      form.captchaCode = ''
      loadCaptcha()
    }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* ============ 图形验证码（风控触发后才显示） ============ */
.captcha-row { display: flex; gap: 10px; align-items: center; width: 100%; }
.captcha-img {
  height: 40px;
  width: 110px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  cursor: pointer;
  object-fit: cover;
  flex: none;
}

.admin-login-page {
  min-height: 100vh;
  display: flex;
  background: #0f1523;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
}

/* ============ 左侧品牌区 ============ */
.login-left {
  flex: 1.2;
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 56px 64px 40px;
  background:
    radial-gradient(ellipse at 20% 10%, rgba(255,80,0, 0.18), transparent 50%),
    radial-gradient(ellipse at 85% 90%, rgba(255,106,26, 0.12), transparent 50%),
    linear-gradient(160deg, #101828 0%, #141d33 55%, #18213a 100%);
  overflow: hidden;
}
.login-left::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255,255,255,.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255,255,255,.03) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse at 30% 40%, black 5%, transparent 75%);
  pointer-events: none;
}
.left-inner { position: relative; z-index: 2; }
.left-logo { display: flex; align-items: center; gap: 12px; }
.left-brand-text { display: flex; flex-direction: column; line-height: 1.3; }
.left-brand-name { font-size: 16px; font-weight: 800; color: #fff; letter-spacing: 1px; }
.left-brand-sub { font-size: 10px; color: #63574e; letter-spacing: 4px; margin-top: 2px; }

.left-title {
  font-size: 34px;
  font-weight: 800;
  color: #fff;
  margin: 46px 0 14px;
  letter-spacing: 2px;
}
.left-title::after {
  content: '';
  display: block;
  width: 46px;
  height: 4px;
  margin-top: 16px;
  border-radius: 2px;
  background: linear-gradient(90deg, #ff5000, #ff8a3d);
  box-shadow: 0 0 12px rgba(255,80,0, 0.5);
}
.left-desc { font-size: 14px; color: #8d8278; line-height: 1.9; margin-bottom: 30px; }
.left-features { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 12px; }
.left-features li { font-size: 13px; color: #a89a8f; display: flex; align-items: center; gap: 10px; }
.feat-dot {
  width: 7px; height: 7px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ff5000, #ff8a3d);
  box-shadow: 0 0 8px rgba(255,80,0, 0.7);
  flex-shrink: 0;
}
.left-footer { position: relative; z-index: 2; font-size: 12px; color: #514741; }

/* ============ 右侧登录区 ============ */
.login-right {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  background:
    radial-gradient(circle at 50% 30%, rgba(255,80,0, 0.06), transparent 55%),
    #0f1523;
}
.login-card {
  width: 400px;
  background: rgba(255, 255, 255, 0.03);
  backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 24px;
  padding: 44px 40px 30px;
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.45);
  position: relative;
}
.card-badge {
  position: absolute;
  top: 26px;
  right: 30px;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 2px;
  padding: 3px 10px;
  border-radius: 6px;
  background: linear-gradient(135deg, #ff5000, #ff7a1a);
  color: #fff;
  box-shadow: 0 3px 10px rgba(255,80,0, 0.4);
}
.card-title { font-size: 26px; font-weight: 800; color: #fff; letter-spacing: 1px; }
.card-sub { color: rgba(255, 255, 255, 0.4); font-size: 13px; margin: 8px 0 30px; }

:deep(.el-input__wrapper) {
  border-radius: 12px;
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.1) inset;
  background: rgba(255, 255, 255, 0.05);
  padding: 4px 14px;
  transition: box-shadow 0.2s, background 0.2s;
}
:deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(255,80,0, 0.4) inset;
  background: rgba(255, 255, 255, 0.08);
}
:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1.5px #ff5000 inset, 0 0 18px rgba(255,80,0, 0.15);
  background: rgba(255, 255, 255, 0.08);
}
:deep(.el-input__inner) { color: #fff; }
:deep(.el-input__inner::placeholder) { color: rgba(255, 255, 255, 0.3); }
:deep(.el-input__prefix) { color: rgba(255, 255, 255, 0.3); }
:deep(.el-form-item) { margin-bottom: 20px; }

.login-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  letter-spacing: 6px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, #ff5000, #ff7a1a);
  box-shadow: 0 6px 20px rgba(255,80,0, 0.35);
  transition: transform 0.15s, box-shadow 0.2s, opacity 0.2s;
  margin-top: 4px;
}
.login-btn:hover {
  background: linear-gradient(135deg, #e64600, #ff5000);
  box-shadow: 0 8px 26px rgba(255,80,0, 0.5);
  transform: translateY(-1px);
}
.login-btn:active { transform: translateY(0); opacity: 0.9; }

.login-footer {
  text-align: center;
  margin-top: 24px;
  font-size: 13px;
}
.login-footer a { color: rgba(255, 255, 255, 0.35); text-decoration: none; transition: color 0.2s; }
.login-footer a:hover { color: #ff8a3d; }

/* ============ 响应式 ============ */
@media (max-width: 960px) {
  .login-left { display: none; }
  .admin-login-page { justify-content: center; }
}
</style>
