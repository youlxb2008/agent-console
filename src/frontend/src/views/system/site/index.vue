<!--
  站点设置
  --------
  配置项：
  1. 平台标题
  2. 主题色（5 套预设：靛蓝紫/翡翠绿/玫瑰红/琥珀橙/石板蓝）
  3. 布局风格（暗色/明色 header/sidebar）
  4. 登录页背景图（预设 + 自定义上传）
  5. 门户质感（下发门户端的三套纯 CSS 质感方案，门户端不做本地切换）
-->
<template>
  <div class="na-page">
    <PageHeader title="站点设置" />

    <div v-if="loading" class="loading-placeholder">
      <el-skeleton :rows="5" animated />
    </div>
    <div v-if="!loading" class="site-settings-container">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="120px" style="max-width: 700px">
        <!-- 平台标题 -->
        <el-form-item label="平台标题" prop="title">
          <el-input v-model="form.title" maxlength="50" show-word-limit placeholder="管理后台显示名称（如 管理端）" />
        </el-form-item>

        <!-- 主题色 -->
        <el-form-item label="主题色">
          <div class="theme-section">
            <!-- 主题色块网格：5 列展示 -->
            <div class="theme-grid">
              <div
                v-for="theme in themes"
                :key="theme.id"
                class="theme-item"
                :class="{ active: form.theme === theme.id }"
                @click="selectTheme(theme.id)"
              >
                <!-- 色卡预览：双色调（主色 + 浅色）展示该主题的气质 -->
                <div
                  class="theme-color-card"
                  :style="{
                    background: `linear-gradient(135deg, ${theme.preview} 0%, ${theme.previewSoft} 100%)`
                  }"
                >
                  <!-- 选中勾号 -->
                  <div v-if="form.theme === theme.id" class="theme-check">
                    <el-icon><Check /></el-icon>
                  </div>
                </div>
                <!-- 主题名 + 描述 -->
                <div class="theme-meta">
                  <div class="theme-name">
                    {{ theme.name }}
                  </div>
                  <div class="theme-desc">
                    {{ theme.description }}
                  </div>
                </div>
              </div>
            </div>

            <!-- 当前主题预览区：实时显示当前选中主题应用到全局的效果 -->
            <div class="theme-preview">
              <div class="theme-preview-label">实时预览</div>
              <div class="theme-preview-buttons">
                <el-button type="primary"> 主要按钮 </el-button>
                <el-button type="success"> 成功 </el-button>
                <el-button type="warning"> 警告 </el-button>
                <el-button type="danger"> 危险 </el-button>
                <el-button type="info"> 信息 </el-button>
                <el-tag type="primary"> 标签 </el-tag>
              </div>
              <div class="theme-preview-tags">
                <el-tag
                  v-for="tag in ['用户', '角色', '菜单', '系统']"
                  :key="tag"
                  :type="tag === '用户' ? 'success' : tag === '角色' ? 'warning' : tag === '菜单' ? 'danger' : 'info'"
                  effect="light"
                >
                  {{ tag }}
                </el-tag>
              </div>
            </div>
          </div>
        </el-form-item>

        <!-- 布局风格 -->
        <el-form-item label="布局风格">
          <div class="layout-section">
            <div class="layout-grid layout-grid-4">
              <!-- 暗色布局 -->
              <div class="layout-item" :class="{ active: form.layoutMode === 'dark' }" @click="selectLayout('dark')">
                <div class="layout-preview-card layout-dark">
                  <div class="layout-preview-header"></div>
                  <div class="layout-preview-sidebar"></div>
                  <div class="layout-preview-main"></div>
                </div>
                <div class="layout-meta">
                  <div class="layout-name">暗色</div>
                  <div class="layout-desc">深色侧边栏</div>
                </div>
                <div v-if="form.layoutMode === 'dark'" class="layout-check">
                  <el-icon><Check /></el-icon>
                </div>
              </div>
              <!-- 明色布局 -->
              <div class="layout-item" :class="{ active: form.layoutMode === 'light' }" @click="selectLayout('light')">
                <div class="layout-preview-card layout-light">
                  <div class="layout-preview-header"></div>
                  <div class="layout-preview-sidebar"></div>
                  <div class="layout-preview-main"></div>
                </div>
                <div class="layout-meta">
                  <div class="layout-name">明色</div>
                  <div class="layout-desc">白色侧边栏</div>
                </div>
                <div v-if="form.layoutMode === 'light'" class="layout-check">
                  <el-icon><Check /></el-icon>
                </div>
              </div>
              <!-- 深蓝布局 -->
              <div class="layout-item" :class="{ active: form.layoutMode === 'navy' }" @click="selectLayout('navy')">
                <div class="layout-preview-card layout-navy">
                  <div class="layout-preview-header"></div>
                  <div class="layout-preview-sidebar"></div>
                  <div class="layout-preview-main"></div>
                </div>
                <div class="layout-meta">
                  <div class="layout-name">深蓝</div>
                  <div class="layout-desc">商务深蓝</div>
                </div>
                <div v-if="form.layoutMode === 'navy'" class="layout-check">
                  <el-icon><Check /></el-icon>
                </div>
              </div>
              <!-- 主题色渲染布局 -->
              <div
                class="layout-item"
                :class="{ active: form.layoutMode === 'tinted' }"
                @click="selectLayout('tinted')"
              >
                <div class="layout-preview-card layout-tinted">
                  <div class="layout-preview-header"></div>
                  <div class="layout-preview-sidebar"></div>
                  <div class="layout-preview-main"></div>
                </div>
                <div class="layout-meta">
                  <div class="layout-name">主题色</div>
                  <div class="layout-desc">浅色渲染</div>
                </div>
                <div v-if="form.layoutMode === 'tinted'" class="layout-check">
                  <el-icon><Check /></el-icon>
                </div>
              </div>
            </div>
          </div>
        </el-form-item>

        <!-- 登录页背景图 -->
        <el-form-item label="登录页背景图">
          <!-- 预设背景图选择区域 -->
          <div class="preset-bg-section">
            <div class="preset-bg-grid">
              <div
                v-for="bg in presetBackgrounds"
                :key="bg.url"
                class="preset-bg-item"
                :class="{ active: form.loginBg === bg.url }"
                :title="bg.label"
                @click="selectPresetBg(bg.url)"
              >
                <img :src="resolveBgUrl(bg.url)" :alt="bg.label" class="preset-bg-thumb" />
                <div class="preset-bg-label">
                  {{ bg.label }}
                </div>
                <!-- 选中标识 -->
                <div v-if="form.loginBg === bg.url" class="preset-bg-check">
                  <el-icon><Check /></el-icon>
                </div>
              </div>
            </div>
          </div>

          <!-- 分隔线 -->
          <el-divider content-position="left"> 或上传自定义图片 </el-divider>

          <!-- 当前选中预览 + 操作区 -->
          <div v-if="form.loginBg" class="login-bg-preview">
            <el-image
              :src="resolveBgUrl(form.loginBg)"
              fit="cover"
              style="width: 320px; height: 180px; border-radius: 8px"
            />
            <div style="margin-top: 8px; display: flex; gap: 8px">
              <el-button type="danger" text size="small" @click="removeBg"> 移除背景图 </el-button>
            </div>
          </div>

          <!-- 上传按钮（使用自定义 http-request 走统一上传接口） -->
          <el-upload
            class="upload-component"
            :show-file-list="false"
            :auto-upload="true"
            :http-request="handleBgUpload"
            :before-upload="beforeUpload"
            accept="image/jpeg,image/png,image/webp,image/gif"
          >
            <el-button type="primary" plain> 选择图片上传 </el-button>
            <template #tip>
              <div class="el-upload__tip">支持 jpg/png/webp/gif，最大 5MB</div>
            </template>
          </el-upload>
        </el-form-item>

        <!-- 门户质感（配置存储于 t_option:portal_texture，门户端启动时经 /api/portal/site 拉取应用） -->
        <el-form-item label="门户质感">
          <div class="texture-section">
            <!-- 质感卡片网格：3 套方案单行展示 -->
            <div class="texture-grid">
              <div
                v-for="tx in portalTextures"
                :key="tx.id"
                class="texture-item"
                :class="{ active: form.portalTexture === tx.id }"
                @click="selectTexture(tx.id)"
              >
                <!-- 纯 CSS mini 预览：模拟该质感在门户端的大致观感 -->
                <div class="texture-preview" :class="`texture-preview-${tx.id}`">
                  <!-- relief 预览额外画一块"浮起"的小卡片示意 -->
                  <div v-if="tx.id === 'relief'" class="texture-relief-block"></div>
                </div>
                <div class="texture-meta">
                  <div class="texture-name">
                    {{ tx.name }}
                  </div>
                  <div class="texture-desc">
                    {{ tx.desc }}
                  </div>
                </div>
                <!-- 选中勾号 -->
                <div v-if="form.portalTexture === tx.id" class="texture-check">
                  <el-icon><Check /></el-icon>
                </div>
              </div>
            </div>
            <div class="texture-tip">作用于 Agent 笔记门户的整体质感；保存后门户端下次加载生效（亮暗模式仍由访客自行切换）</div>
          </div>
        </el-form-item>

        <!-- 保存按钮 -->
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave"> 保存设置 </el-button>
          <el-button @click="loadData"> 重新加载 </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 站点设置页面
 *
 * 功能：
 * 1. 修改平台标题
 * 2. 切换主题色（5 套预设，实时预览）
 * 3. 切换登录页背景图（预设 + 上传）
 * 4. 选择门户质感（三套纯 CSS 方案，保存后门户端下次加载时生效）
 */
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Check } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { getSiteConfig, saveSiteConfig } from '@/api/system/site'
import { uploadImage } from '@/api/system/upload'
import { useSiteStore } from '@/store/site'
import { useThemeStore } from '@/store/theme'
import { applyTheme } from '@/composables/useApplyTheme'
import { stashThemeDraft, stashLayoutDraft, clearThemeDraft } from '@/composables/useSiteSettings'
import { DEFAULT_THEME_ID, type LayoutMode } from '@/assets/css/themes'

/** 站点配置 store（保存后同步更新，让 header / login 立即生效） */
const siteStore = useSiteStore()
/** 主题 store（切换主题时同步） */
const themeStore = useThemeStore()
/** 全部主题预设列表 */
const themes = themeStore.themes

/** 表单引用 */
const formRef = ref<FormInstance>()
/** 加载状态 */
const loading = ref(false)
/** 保存中状态 */
const saving = ref(false)

/** 表单数据 */
const form = reactive({
  /** 平台标题 */
  title: '',
  /** 主题色 id */
  theme: DEFAULT_THEME_ID,
  /** 布局风格：dark / light */
  layoutMode: 'dark' as LayoutMode,
  /** 登录页背景图路径（预设路径或上传后的相对路径） */
  loginBg: '',
  /** 门户质感方案 id：aurora / relief / grid（存储于 t_option:portal_texture，保存后经 /api/portal/site 下发给门户端只读应用） */
  portalTexture: 'aurora'
})

/** 表单校验规则 */
const formRules: FormRules = {
  title: [
    { required: true, message: '平台标题不能为空', trigger: 'blur' },
    { min: 1, max: 50, message: '标题长度 1-50 个字符', trigger: 'blur' }
  ]
}

/**
 * 自定义上传背景图（走统一上传接口）
 * @param options el-upload 的 UploadRequestOptions
 */
async function handleBgUpload(options: any): Promise<void> {
  const file = options.file as File
  try {
    const res = await uploadImage(file, 'login-bg')
    const url = res.data?.url
    if (url) {
      form.loginBg = url
      ElMessage.success('背景图上传成功')
    } else {
      ElMessage.error('上传成功但未返回图片地址')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '背景图上传失败')
  }
}

/**
 * 预设背景图列表
 *
 * 图片存放在 public/login-bg/ 目录下，通过 Vite 静态服务直接访问。
 * 路径以 /login-bg/ 开头，Vite 会自动从 public 目录提供。
 */
const presetBackgrounds = [
  { label: '科技蓝', url: '/login-bg/bg-01-tech-blue_001.jpg' },
  { label: '紫韵流光', url: '/login-bg/bg-02-purple-wave_001.jpg' },
  { label: '暗金商务', url: '/login-bg/bg-03-dark-gold_001.jpg' },
  { label: '赛博网格', url: '/login-bg/bg-04-cyber-grid_001.jpg' },
  { label: '翡翠幽光', url: '/login-bg/bg-05-emerald_001.jpg' },
  { label: '星空深邃', url: '/login-bg/bg-06-starry_001.jpg' }
]

/**
 * 门户质感方案列表（供选择卡片渲染）
 * id 值域必须与门户端 main.css 的 html[data-texture='...'] 选择器保持一致
 */
const portalTextures = [
  { id: 'aurora', name: '柔光', desc: '彩色光晕 + 玻璃面板' },
  { id: 'relief', name: '浮雕', desc: '极简立体卡片' },
  { id: 'grid', name: '网格', desc: '16px 钴蓝网格 + 顶光' }
]

/**
 * 选择主题色
 * 1. 立即切换本地预览（applyTheme），不立即持久化到后端
 * 2. 同时暂存到 localStorage，刷新页面也能恢复（避免误丢失）
 * 3. 用户点保存后再写入数据库
 * @param id 主题 id
 */
function selectTheme(id: string): void {
  form.theme = id
  // 实时预览：立即应用主题到 CSS 变量（保留当前布局风格）
  themeStore.setThemeId(id)
  applyTheme(id, form.layoutMode)
  // 暂存到 localStorage：用户没保存就刷新页面也能保持主题
  stashThemeDraft(id)
}

/**
 * 选择布局风格
 * 1. 立即切换本地预览（applyTheme），不立即持久化到后端
 * 2. 同时暂存到 localStorage
 * @param mode 布局风格 dark/light
 */
function selectLayout(mode: LayoutMode): void {
  form.layoutMode = mode
  // 实时预览：立即应用布局风格到 CSS 变量
  themeStore.setLayoutMode(mode)
  applyTheme(form.theme, mode)
  // 暂存到 localStorage
  stashLayoutDraft(mode)
}

/**
 * 选择门户质感
 * 仅更新表单值（门户端质感不在管理后台实时预览），点保存时才持久化
 * @param id 质感方案 id：aurora / relief / grid
 */
function selectTexture(id: string): void {
  form.portalTexture = id
}

/**
 * 选择预设背景图
 * @param url 预设图片路径
 */
function selectPresetBg(url: string): void {
  // 再次点击取消选择
  form.loginBg = form.loginBg === url ? '' : url
}

/**
 * 将相对路径背景图转为完整 URL
 * @param relativePath 如 /uploads/login-bg/xxx.png 或 /login-bg/xxx.jpg
 * @returns 可直接用于 img src 的完整 URL
 */
function resolveBgUrl(relativePath: string): string {
  if (!relativePath) return ''
  // 预设图以 /login-bg/ 开头，是 Vite 静态资源
  // M1.4：base 切 /admin/ 后须拼 BASE_URL 前缀，否则 nginx 下指向站点根 404
  if (relativePath.startsWith('/login-bg/')) return `${import.meta.env.BASE_URL}${relativePath.slice(1)}`
  // 如果已经是完整 URL 则直接返回
  if (relativePath.startsWith('http')) return relativePath
  // 上传的图片需要拼接 API base URL
  const baseURL = import.meta.env.VITE_API_BASE_URL || ''
  return `${baseURL}${relativePath}`
}

/**
 * 上传前校验：检查文件类型和大小
 * @param file 待上传的文件
 * @returns true 允许上传，false 拦截
 */
function beforeUpload(file: File): boolean {
  const allowedTypes = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']
  if (!allowedTypes.includes(file.type)) {
    ElMessage.error('仅支持 jpg/png/webp/gif 格式')
    return false
  }
  // 限制最大 5MB
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片大小不能超过 5MB')
    return false
  }
  return true
}

/** 移除背景图：清空表单字段 */
function removeBg(): void {
  form.loginBg = ''
}

/**
 * 保存设置
 * 1. 表单校验
 * 2. 将前端字段名映射为后端 key-value 格式
 * 3. 调用保存接口
 * 4. 同步更新 store（让 header / login 立即生效）
 */
async function handleSave(): Promise<void> {
  if (!formRef.value) return
  // 表单校验
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    // 前端字段名 → 后端 option_key 映射
    const res = await saveSiteConfig({
      site_title: form.title,
      site_theme: form.theme,
      site_layout_mode: form.layoutMode,
      site_login_bg: form.loginBg,
      portal_texture: form.portalTexture
    })
    // 同步更新 store（让 header / login / 主题立即生效）
    siteStore.setSite({
      title: form.title,
      theme: form.theme,
      layoutMode: form.layoutMode,
      loginBg: form.loginBg,
      portalTexture: form.portalTexture
    })
    // 同步主题 + 布局风格
    themeStore.setThemeId(form.theme)
    themeStore.setLayoutMode(form.layoutMode)
    applyTheme(form.theme, form.layoutMode)
    // 后端已持久化，清除 localStorage 暂存
    clearThemeDraft()
    // 优先展示后端返回的消息，兜底用默认文案
    ElMessage.success(res?.msg || '站点设置已保存')
  } catch (err: unknown) {
    const msg = (err as Error)?.message || '保存失败'
    ElMessage.error(msg)
  } finally {
    saving.value = false
  }
}

/**
 * 页面加载时获取当前配置
 * 成功后填充表单，失败则提示错误
 */
async function loadData() {
  loading.value = true
  try {
    const res = await getSiteConfig()
    const data = res.data
    if (data) {
      // 后端返回 key-value Map，用 option_key 映射到表单字段
      form.title = data.site_title || ''
      form.theme = data.site_theme || DEFAULT_THEME_ID
      form.layoutMode = (data.site_layout_mode as LayoutMode) || 'dark'
      form.loginBg = data.site_login_bg || ''
      // 门户质感：缺省回退 aurora（与后端 DEFAULT_PORTAL_TEXTURE 一致）
      form.portalTexture = data.portal_texture || 'aurora'
      // 同步应用当前主题 + 布局风格
      themeStore.setThemeId(form.theme)
      themeStore.setLayoutMode(form.layoutMode)
      applyTheme(form.theme, form.layoutMode)
    }
  } catch {
    ElMessage.error('加载站点配置失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
/* 加载中占位 */
.loading-placeholder {
  padding: 20px;
  background: var(--el-bg-color);
  border-radius: 8px;
}

/* 设置表单容器 */
.site-settings-container {
  padding: 20px;
  background: var(--el-bg-color);
  border-radius: 8px;
}

/* ===== 主题色选择 ===== */
.theme-section {
  width: 100%;
}

/* 主题色块网格：5 列展示（响应式收窄） */
.theme-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  width: 100%;
  max-width: 560px;
  margin-bottom: 16px;
}

/* 单个主题色块 */
.theme-item {
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  border: 2px solid transparent;
  transition: all 0.2s ease;
  background: var(--el-fill-color-light);
}

.theme-item:hover {
  border-color: var(--na-primary-hover);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.theme-item.active {
  border-color: var(--na-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

/* 主题色卡：双色渐变预览 */
.theme-color-card {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
}

/* 主题色卡选中勾号 */
.theme-check {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #ffffff;
  color: var(--na-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: bold;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}

/* 主题名称 + 描述 */
.theme-meta {
  padding: 6px 8px;
}

.theme-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  line-height: 1.4;
}

.theme-desc {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 2px;
}

/* 主题预览区 */
.theme-preview {
  padding: 16px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  border: 1px dashed var(--el-border-color);
}

.theme-preview-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}

.theme-preview-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.theme-preview-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

/* ===== 布局风格选择 ===== */
.layout-section {
  width: 100%;
}

.layout-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  width: 100%;
  max-width: 320px;
}

.layout-grid-4 {
  grid-template-columns: repeat(4, 1fr);
  max-width: 560px;
}

.layout-item {
  position: relative;
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  border: 2px solid transparent;
  transition: all 0.2s ease;
  background: var(--el-fill-color-light);
}

.layout-item:hover {
  border-color: var(--na-primary-hover);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.layout-item.active {
  border-color: var(--na-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

/* 布局预览缩略图：模拟 header + sidebar + main 三段式 */
.layout-preview-card {
  display: grid;
  grid-template-rows: 12px 1fr;
  grid-template-columns: 28px 1fr;
  width: 100%;
  aspect-ratio: 16 / 10;
  border-radius: 4px 4px 0 0;
  overflow: hidden;
  gap: 1px;
}

/* 暗色布局预览 */
.layout-dark .layout-preview-header {
  background: #1e293b;
  grid-column: 1 / -1;
}
.layout-dark .layout-preview-sidebar {
  background: #0f172a;
}
.layout-dark .layout-preview-main {
  background: #f5f7fa;
}

/* 明色布局预览 */
.layout-light .layout-preview-header {
  background: #ffffff;
  border-bottom: 1px solid #e5e7eb;
  grid-column: 1 / -1;
}
.layout-light .layout-preview-sidebar {
  background: #ffffff;
  border-right: 1px solid #e5e7eb;
}
.layout-light .layout-preview-main {
  background: #f5f7fa;
}

/* 深蓝布局预览 */
.layout-navy .layout-preview-header {
  background: #1e3a5f;
  grid-column: 1 / -1;
}
.layout-navy .layout-preview-sidebar {
  background: #0c2340;
}
.layout-navy .layout-preview-main {
  background: #f5f7fa;
}

/* 主题色渲染布局预览（用紫色代表，实际会随主题变化） */
.layout-tinted .layout-preview-header {
  background: #eef2ff;
  border-bottom: 1px solid #e0e7ff;
  grid-column: 1 / -1;
}
.layout-tinted .layout-preview-sidebar {
  background: #eef2ff;
  border-right: 1px solid #e0e7ff;
}
.layout-tinted .layout-preview-main {
  background: #f5f7fa;
}

.layout-meta {
  padding: 6px 8px;
}

.layout-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  line-height: 1.4;
}

.layout-desc {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 2px;
}

.layout-check {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--na-primary);
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}

/* ===== 门户质感选择 ===== */
.texture-section {
  width: 100%;
}

/* 质感卡片网格：3 列展示（宽度与主题色网格对齐） */
.texture-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  width: 100%;
  max-width: 560px;
  margin-bottom: 8px;
}

/* 单个质感卡片（结构同 layout-item：相对定位供勾号绝对定位） */
.texture-item {
  position: relative;
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  border: 2px solid transparent;
  transition: all 0.2s ease;
  background: var(--el-fill-color-light);
}

.texture-item:hover {
  border-color: var(--na-primary-hover);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.texture-item.active {
  border-color: var(--na-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

/* 质感 mini 预览：深底画布模拟门户端暗色下的观感 */
.texture-preview {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden;
}

/* aurora：双光斑径向渐变（蓝 + 紫），对应门户 --tx-glow 的大致气质 */
.texture-preview-aurora {
  background:
    radial-gradient(circle at 30% 30%, rgba(59, 130, 246, 0.35), transparent 60%),
    radial-gradient(circle at 70% 65%, rgba(147, 51, 234, 0.3), transparent 55%),
    #0f1420;
}

/* relief：深底画布 + 顶部聚光（对应门户 relief 的 --tx-bg-image 顶部亮带），
   让"浮起"小卡片的内高光/投影有深色底可衬托；此前漏了该类导致画布透明、
   深色卡片浮在浅色页面上像一块黑砖 */
.texture-preview-relief {
  background: linear-gradient(180deg, #1b202a 0%, #0f1420 70%);
}

/* relief：深底上一块"浮起"的小卡片（inset 高光 + 投影，对应门户浮雕卡片） */
.texture-relief-block {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 56%;
  height: 52%;
  transform: translate(-50%, -50%);
  border-radius: 4px;
  background: linear-gradient(180deg, #1d2534, #171e2b);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.08),
    0 4px 10px rgba(0, 0, 0, 0.45);
}

/* grid：12px 钴蓝色相 hairline 网格（对应门户 --tx-grid-line） */
.texture-preview-grid {
  background:
    repeating-linear-gradient(0deg, rgba(96, 165, 250, 0.16) 0, rgba(96, 165, 250, 0.16) 1px, transparent 1px, transparent 12px),
    repeating-linear-gradient(90deg, rgba(96, 165, 250, 0.16) 0, rgba(96, 165, 250, 0.16) 1px, transparent 1px, transparent 12px),
    #0f1420;
}

/* 质感名称 + 描述 */
.texture-meta {
  padding: 6px 8px;
}

.texture-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  line-height: 1.4;
}

.texture-desc {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 2px;
}

/* 质感卡片选中勾号（白底圆 + 主题色勾，与主题色卡片勾号视觉一致） */
.texture-check {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #ffffff;
  color: var(--na-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: bold;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}

/* 提示文案：说明生效方式（保存后门户下次加载生效） */
.texture-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}

/* ===== 背景图预览区域 ===== */
.login-bg-preview {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  margin-bottom: 16px;
}

/* 预设背景图选择区域 */
.preset-bg-section {
  width: 100%;
}

/* 预设背景图网格：3 列布局 */
.preset-bg-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  width: 100%;
  max-width: 560px;
}

/* 单个预设背景图卡片 */
.preset-bg-item {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  border: 2px solid transparent;
  transition: all 0.2s ease;
  aspect-ratio: 16 / 9;
}

/* 悬浮效果 */
.preset-bg-item:hover {
  border-color: var(--na-primary-hover);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

/* 选中效果 */
.preset-bg-item.active {
  border-color: var(--na-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

/* 缩略图 */
.preset-bg-thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* 标签文字 */
.preset-bg-label {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 4px 8px;
  background: linear-gradient(transparent, rgba(0, 0, 0, 0.6));
  color: #fff;
  font-size: 12px;
  text-align: center;
}

/* 选中勾号 */
.preset-bg-check {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--na-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}

/* 上传组件：增加顶部和左侧间距 */
.upload-component {
  margin-top: 12px;
  margin-left: 16px;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .theme-grid {
    grid-template-columns: repeat(3, 1fr);
  }

  .texture-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
