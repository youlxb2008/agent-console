<!--
  登录页（玻璃拟态版）
  --------------------
  风格：现代玻璃拟态（Glassmorphism）+ 动态光斑背景
  - 全屏背景图：使用站点配置的 loginBg（默认从预设 login-bg 随机）
  - 背景层：3 个动态漂浮光斑（CSS 动画）
  - 卡片：半透明白 + backdrop-filter blur(20px) + 细白边
  - 按钮：渐变 + 悬停上移
  - 入场动画：上浮 + 渐入 700ms
-->
<template>
  <div class="login-wrap">
    <!-- 背景层 -->
    <div class="bg-image" :style="loginBgStyle" />
    <div class="bg-overlay" />
    <div class="bg-orb bg-orb-1" />
    <div class="bg-orb bg-orb-2" />
    <div class="bg-orb bg-orb-3" />

    <!-- 玻璃卡片 -->
    <div class="login-card">
      <div class="login-header">
        <div class="brand">
          <div class="brand-logo">
            <el-icon :size="22"><component :is="Key" /></el-icon>
          </div>
          <div class="brand-name">{{ loginTitle }}</div>
        </div>
        <h1 class="login-title">欢迎回来</h1>
        <p class="login-subtitle">请登录您的账号以继续</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="0" class="login-form" @keyup.enter="handleLogin">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" size="large" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="handleLogin">
          登 录
        </el-button>
      </el-form>

      <div class="login-divider"><span>安全登录</span></div>
    </div>

    <div class="login-footer">{{ loginTitle }} &copy; {{ year }}</div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { User, Lock, Key } from '@element-plus/icons-vue'
import { login } from '@/api/auth'
import { getMyMenuTree } from '@/api/system/menu'
import { useUserStore } from '@/store/user'
import { usePermissStore } from '@/store/permiss'
import { useMenuStore } from '@/store/menu'
import { useTagsStore } from '@/store/tags'
import { useSiteStore } from '@/store/site'
import { setAuthenticated, registerDynamicRoutes } from '@/router'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const permissStore = usePermissStore()
const menuStore = useMenuStore()
const tagsStore = useTagsStore()
const siteStore = useSiteStore()

/** 动态平台标题（从站点配置 store 获取，默认 管理端） */
const loginTitle = computed(() => siteStore.title || '管理端')

/**
 * 背景图候选列表（与 public/login-bg/ 下文件一一对应）
 * 后台未配置时，按天随机选一张，避免每次刷新都换（避免闪烁）
 * M1.4：base 切 /admin/ 后 public 静态资源须拼 BASE_URL 前缀，
 * 否则 nginx 下 /login-bg/... 会指向站点根（门户端）而 404
 */
const PRESET_BG_LIST = [
  `${import.meta.env.BASE_URL}login-bg/bg-01-tech-blue_001.jpg`,
  `${import.meta.env.BASE_URL}login-bg/bg-02-purple-wave_001.jpg`,
  `${import.meta.env.BASE_URL}login-bg/bg-03-dark-gold_001.jpg`,
  `${import.meta.env.BASE_URL}login-bg/bg-04-cyber-grid_001.jpg`,
  `${import.meta.env.BASE_URL}login-bg/bg-05-emerald_001.jpg`,
  `${import.meta.env.BASE_URL}login-bg/bg-06-starry_001.jpg`
]

/**
 * 动态背景图样式
 * - 用户配置过：用用户的
 * - 未配置：从预设里按天随机选一张，避免每次刷新都换
 * - 上传的图片需拼接 API base URL
 */
const loginBgStyle = computed(() => {
  const bg = (siteStore.config as any)?.loginBg
  let bgUrl: string
  if (!bg) {
    // 按天随机：基于日期种子，同一天打开页面看到的是同一张
    const dayOfYear = Math.floor((Date.now() - new Date(new Date().getFullYear(), 0, 0).getTime()) / 86400000)
    bgUrl = PRESET_BG_LIST[dayOfYear % PRESET_BG_LIST.length]
  } else if (bg.startsWith('/login-bg/')) {
    // 存量配置值以 /login-bg/ 开头（public 静态资源），拼 BASE_URL 前缀
    bgUrl = `${import.meta.env.BASE_URL}${bg.slice(1)}`
  } else if (bg.startsWith('http')) {
    bgUrl = bg
  } else {
    const baseURL = import.meta.env.VITE_API_BASE_URL || ''
    bgUrl = `${baseURL}${bg}`
  }
  return {
    backgroundImage: `url(${bgUrl})`,
    backgroundSize: 'cover',
    backgroundPosition: 'center'
  }
})

/** 当前年份（用于页脚） */
const year = new Date().getFullYear()

/** 加载状态 */
const loading = ref(false)
/** 表单引用 */
const formRef = ref<FormInstance>()

/** 登录表单数据（不预填默认值） */
const form = reactive({
  username: '',
  password: ''
})

/** 表单校验规则 */
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不少于 6 位', trigger: 'blur' }
  ]
}

// 进入登录页时清空标签，避免登录后混乱
onMounted(() => {
  tagsStore.clear()
})

/**
 * 处理登录提交
 * 流程同 NodeAdmin 模式：登录 → 存 token/perms → 拉菜单 → 注册动态路由 → 跳转
 */
async function handleLogin() {
  await formRef.value?.validate()
  loading.value = true
  try {
    const res = await login(form)
    const data = res.data
    userStore.setLoginInfo(data)
    permissStore.setPerms(data.permissions || [])
    const menuRes = await getMyMenuTree()
    const menus = menuRes.data || []
    menuStore.setMenus(menus)
    registerDynamicRoutes(router, menus)
    setAuthenticated(true)
    ElMessage.success('登录成功')
    const redirect = (route.query.redirect as string) || '/'
    const target = userStore.mustChangePassword ? '/profile/password' : redirect
    // 必须强制改密时给个明确提示（不能依赖页面渲染，因为页内引导需 store 状态正确）
    if (userStore.mustChangePassword) {
      ElMessage.warning('检测到您正在使用初始密码，请尽快修改以保障账号安全')
    }
    // 使用 SPA 跳转而不是 location.assign，避免整页刷新导致 store 状态丢失
    // 整页刷新会重建 Vue 实例，userStore.mustChangePassword 重置为 false，导致密码页引导卡片不显示
    router.push(target)
  } catch (e) {
    // 错误由请求拦截器统一提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* ============== 根容器 ============== */
.login-wrap {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  isolation: isolate;
}

/* ============== 背景层 ============== */
/* 背景图 */
.bg-image {
  position: absolute;
  inset: 0;
  z-index: -3;
  animation: bg-zoom 30s ease-in-out infinite alternate;
}

/* 背景遮罩：让背景图暗一点，卡片更突出 */
.bg-overlay {
  position: absolute;
  inset: 0;
  z-index: -2;
  background: linear-gradient(
    135deg,
    rgba(15, 23, 42, 0.55) 0%,
    rgba(30, 58, 138, 0.35) 50%,
    rgba(139, 92, 246, 0.25) 100%
  );
  backdrop-filter: blur(2px);
}

/* 动态漂浮光斑（玻璃质感的灵魂） */
.bg-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.5;
  z-index: -1;
  pointer-events: none;
}

.bg-orb-1 {
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, #6366f1, transparent);
  top: -100px;
  left: -100px;
  animation: float-1 20s ease-in-out infinite;
}

.bg-orb-2 {
  width: 500px;
  height: 500px;
  background: radial-gradient(circle, #ec4899, transparent);
  bottom: -150px;
  right: -150px;
  animation: float-2 25s ease-in-out infinite;
}

.bg-orb-3 {
  width: 300px;
  height: 300px;
  background: radial-gradient(circle, #06b6d4, transparent);
  top: calc(50% - 150px);
  left: calc(60% - 150px);
  animation: float-3 18s ease-in-out infinite;
}

@keyframes float-1 {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(80px, 120px);
  }
}

@keyframes float-2 {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(-100px, -80px);
  }
}

@keyframes float-3 {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(-60px, 100px);
  }
}

@keyframes bg-zoom {
  0% {
    transform: scale(1);
  }
  100% {
    transform: scale(1.08);
  }
}

/* ============== 玻璃卡片 ============== */
.login-card {
  position: relative;
  width: 420px;
  padding: 40px 36px 32px;
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: 16px;
  box-shadow:
    0 25px 50px -12px rgba(0, 0, 0, 0.35),
    0 0 0 1px rgba(255, 255, 255, 0.1) inset;
  animation: card-enter 0.7s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes card-enter {
  0% {
    opacity: 0;
    transform: translateY(20px) scale(0.98);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

/* ============== 头部品牌区 ============== */
.login-header {
  text-align: center;
  margin-bottom: 28px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 24px;
}

.brand-logo {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--na-primary-gradient);
  color: #fff;
  border-radius: 10px;
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.4);
}

.brand-name {
  font-size: 16px;
  font-weight: 600;
  color: #1e293b;
  letter-spacing: 0.5px;
}

.login-title {
  font-size: 28px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 6px;
  letter-spacing: 1px;
}

.login-subtitle {
  font-size: 13px;
  color: #64748b;
  margin: 0;
}

/* ============== 表单 ============== */
.login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.login-form :deep(.el-input__wrapper) {
  border-radius: 10px;
  padding: 4px 12px;
  background: rgba(255, 255, 255, 0.7);
  box-shadow: 0 0 0 1px rgba(148, 163, 184, 0.25) inset;
  transition: all 0.2s ease;
}

.login-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(99, 102, 241, 0.5) inset;
}

.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px var(--na-primary) inset,
    0 0 0 4px rgba(99, 102, 241, 0.15);
}

.login-form :deep(.el-input__inner) {
  height: 44px;
  font-size: 14px;
}

/* ============== 登录按钮 ============== */
.login-btn {
  width: 100%;
  height: 46px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 2px;
  border-radius: 10px;
  margin-top: 4px;
  background: var(--na-primary-gradient);
  border: none;
  box-shadow: 0 4px 14px rgba(99, 102, 241, 0.35);
  transition: all 0.25s ease;
}

.login-btn:hover:not(.is-loading) {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px rgba(99, 102, 241, 0.5);
  background: var(--na-primary-gradient-hover);
}

.login-btn:active {
  transform: translateY(0);
}

/* ============== 分割线 + 安全标识 ============== */
.login-divider {
  margin-top: 20px;
  text-align: center;
  position: relative;
  font-size: 11px;
  color: #94a3b8;
  letter-spacing: 1px;
}

.login-divider::before,
.login-divider::after {
  content: '';
  position: absolute;
  top: 50%;
  width: 35%;
  height: 1px;
  background: linear-gradient(to right, transparent, rgba(148, 163, 184, 0.3), transparent);
}

.login-divider::before {
  left: 0;
}
.login-divider::after {
  right: 0;
}

/* ============== 页脚 ============== */
.login-footer {
  position: absolute;
  bottom: 20px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
  letter-spacing: 0.5px;
}
</style>
