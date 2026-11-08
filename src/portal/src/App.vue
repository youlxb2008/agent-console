<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { siteTitle, siteDescription, loadSite } from "@/store/site";
import { loadNotebooks } from "@/store/notebook";

/**
 * 门户布局（v6 双主题 × 三质感套件）
 * - 顶栏：扁平 + 全宽 hairline 底边；左 logo + 中导航区（智能体 / 笔记本；目标/台账入口已移除）
 *   + 右（主题切换 / 管理后台）
 * - 主区：容器统一 var(--container-max)，承载 HomeView / NotesView / AgentsView
 * - 页脚：极简一行（去掉硬编码假统计与分割线）
 * - 主题：亮色为默认；data-theme 写在 <html> 上，localStorage 持久化，
 *   index.html 内联脚本已在首帧前同步，避免刷新闪烁
 * - 质感：data-texture 写在 <html> 上（aurora 柔光 / relief 浮雕 / grid 网格），
 *   由管理平台"站点设置"统一下发（portal_texture 配置项），门户端经 loadSite()
 *   拉取后只读应用，不做本地切换；localStorage 缓存上次下发值供首帧还原
 */

const route = useRoute();
const router = useRouter();

/** 当前主题（与 <html data-theme> 保持同步） */
const theme = ref<"dark" | "light">("light");

/**
 * 导航高亮（手动判定，不用 RouterLink 自动 active）：
 * 「智能体」归 /agents/*；「笔记本」归 /notebooks/*（承接页与笔记页）；
 * 首页 / 为站点门面，不归属任何导航项。
 * 注：/objectives、/ledger 路由仍保留（详情页内跳转可达），只是顶部菜单不再展示入口
 */
const isAgentsActive = computed<boolean>(() =>
  route.path.startsWith("/agents"),
);
const isNotebooksActive = computed<boolean>(() =>
  route.path.startsWith("/notebooks"),
);
/** M1 门户包：「消息流转」归 /bus */
const isBusActive = computed<boolean>(() => route.path.startsWith("/bus"));

/** 切换主题：写 data-theme + 持久化 */
function toggleTheme(): void {
  theme.value = theme.value === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = theme.value;
  try {
    localStorage.setItem("portal-theme", theme.value);
  } catch {
    /* 隐私模式等 localStorage 不可用场景：静默降级为仅会话内生效 */
  }
}

onMounted(() => {
  // index.html 内联脚本已写入 data-theme / data-texture；这里把主题的 Vue 状态对齐到实际值
  // （质感由 store 的 loadSite() 按管理平台配置应用，无需本地状态）
  theme.value =
    document.documentElement.dataset.theme === "light" ? "light" : "dark";
  void loadSite();
  void loadNotebooks();
});
</script>

<template>
  <div class="portal-wrap">
    <!-- 顶栏 -->
    <header class="portal-header">
      <div class="navbar">
        <RouterLink to="/" class="brand" :title="siteTitle">
          <img class="brand-logo" src="/logo.png" alt="Agent Console" />
          <span class="brand-title">{{ siteTitle }}</span>
        </RouterLink>

        <!-- 顶部主导航：智能体 / 笔记本 / 消息流转（目标/台账已按需求从顶部菜单移除，路由保留可直达） -->
        <nav class="nav-links">
          <RouterLink
            to="/agents"
            class="nav-link"
            :class="{ 'is-active': isAgentsActive }"
            >智能体</RouterLink
          >
          <RouterLink
            to="/notebooks"
            class="nav-link"
            :class="{ 'is-active': isNotebooksActive }"
            >笔记本</RouterLink
          >
          <!-- M1 门户包：消息流转入口（只加一项不重排） -->
          <RouterLink to="/bus" class="nav-link" :class="{ 'is-active': isBusActive }"
            >消息流转</RouterLink
          >
        </nav>

        <!-- 中间导航区：弹性占位（保持左右两端对齐的布局基准） -->
        <div class="nav-center"></div>

        <div class="portal-user">
          <!-- 亮暗模式切换：暗色下显示太阳（点击切亮色），亮色下显示月亮 -->
          <button
            type="button"
            class="theme-toggle"
            :title="theme === 'dark' ? '切换到亮色模式' : '切换到暗色模式'"
            :aria-label="theme === 'dark' ? '切换到亮色模式' : '切换到暗色模式'"
            @click="toggleTheme"
          >
            <!-- 太阳（当前暗色 → 点击切亮色） -->
            <svg
              v-if="theme === 'dark'"
              viewBox="0 0 24 24"
              width="16"
              height="16"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
            >
              <circle cx="12" cy="12" r="4" />
              <path
                d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"
              />
            </svg>
            <!-- 月亮（当前亮色 → 点击切暗色） -->
            <svg
              v-else
              viewBox="0 0 24 24"
              width="16"
              height="16"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
            </svg>
          </button>

          <a href="/admin/" class="admin-entry" title="进入管理后台">
            管理后台
            <span class="arrow" aria-hidden="true">↗</span>
          </a>
        </div>
      </div>
    </header>

    <!-- 内容区 -->
    <!--
      page-scroll：普通页面的统一滚动容器（滚动条在内容区右缘内侧，
      而非浏览器窗口边缘）。笔记页三栏自管滚动，通过 :has 识别后关闭。
    -->
    <main class="portal-main">
      <div class="page-scroll">
        <RouterView />
      </div>
    </main>

    <!-- 页脚：极简一行弱化小字（无分割线、无假统计） -->
    <footer class="portal-footer">
      <span class="footer-line">{{ siteTitle }}</span>
      <span v-if="siteDescription" class="footer-sep" aria-hidden="true"
        >·</span
      >
      <span v-if="siteDescription" class="footer-line">{{
        siteDescription
      }}</span>
    </footer>
  </div>
</template>

<style scoped>
/* ============ 页面骨架（app-shell：视口内固定，整页不滚动） ============ */
.portal-wrap {
  height: 100%;
  display: flex;
  flex-direction: column;
  color: var(--text-main);
  width: 100%;
}

/* ============ 顶栏（流内固定：整页不滚动，天然常驻视口顶部） ============ */
.portal-header {
  flex-shrink: 0;
  padding: 20px var(--container-pad) 14px;
  /* 全宽 hairline：顶栏与内容区明确分隔（亮色下尤为必要） */
  border-bottom: 1px solid var(--border);
}

.navbar {
  /* 与内容容器同一宽度基准，大屏下不再两侧空白 */
  max-width: var(--container-max);
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  color: inherit;
  padding: 4px 0;
}

.brand:hover {
  color: var(--text-main);
}

/* ============ 顶部导航 ============ */
.nav-links {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: 18px;
}
.nav-link {
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  padding: 6px 10px;
  border-radius: 8px;
  text-decoration: none;
  cursor: pointer;
  transition:
    color 0.2s ease,
    background 0.2s ease;
  white-space: nowrap;
}
.nav-link:hover {
  color: var(--accent);
  background: var(--accent-soft);
}
/* 当前激活项：accent 文字 + 淡底（与 hover 区分：多一层下边线强化所在位置） */
.nav-link.is-active {
  color: var(--accent);
  background: var(--accent-soft);
  font-weight: 600;
}

/* logo：品牌图（kawaii 猫头 app icon），方形圆角 */
.brand-logo {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  object-fit: cover;
  display: block;
  flex-shrink: 0;
}

.brand-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-main);
  letter-spacing: 0.2px;
}

.portal-user {
  display: flex;
  align-items: center;
  gap: 6px;
}

/* ============ 顶栏中间导航区：弹性占位 ============ */
.nav-center {
  flex: 1;
  min-width: 0;
}

/* 主题切换：方形 ghost 图标按钮 */
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition:
    color 0.2s ease,
    background 0.2s ease;
}

.theme-toggle:hover {
  color: var(--accent);
  background: var(--bg-panel-2);
}

/* 管理后台入口：纯文字 + 箭头 */
.admin-entry {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  padding: 6px 12px;
  border-radius: 8px;
  transition:
    color 0.2s ease,
    background 0.2s ease;
  text-decoration: none;
}

.admin-entry:hover {
  color: var(--accent);
  background: var(--bg-panel-2);
}

.admin-entry .arrow {
  font-size: 12px;
}

/* ============ 内容区 ============ */
.portal-main {
  flex: 1;
  /* app-shell：main 本身不滚，高度 = 视口 - 顶栏 - 页脚，滚动交给 page-scroll / 各栏 */
  min-height: 0;
  overflow: hidden;
}

/* ---- 普通页面统一滚动容器：滚动条在内容区右缘内侧 ---- */
.page-scroll {
  height: 100%;
  overflow-y: auto;
  padding: 8px 0 32px;
}

/* 笔记页三栏自管滚动（.notebook 高度即容器高度，无溢出）：
   关闭外层滚动与 padding，让三栏撑满整个内容区 */
.page-scroll:has(> .notebook) {
  overflow: hidden;
  padding: 0;
}

/* ============ 页脚：一行居中弱化小字（app-shell 下常驻视口底部） ============ */
.portal-footer {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 20px var(--container-pad) 24px;
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-mono);
}

.footer-sep {
  opacity: 0.6;
}

/* ============ 响应式 ============ */
@media (max-width: 768px) {
  .brand-title {
    font-size: 15px;
  }
}

/* 960px 以下回退传统整页滚动（与 main.css 的视口解锁联动） */
@media (max-width: 960px) {
  .portal-wrap {
    height: auto;
    min-height: 100vh;
  }

  .portal-main {
    overflow: visible;
  }

  .page-scroll,
  .page-scroll:has(> .notebook) {
    overflow-y: visible;
    padding: 8px 0 32px;
  }
}

/* 640px 以下：顶栏双行 —— 品牌行在上（logo+站名 | 主题切换+管理后台），导航独占一行在下 */
@media (max-width: 640px) {
  .portal-header {
    padding: 12px 14px 0;
  }
  .navbar {
    flex-wrap: wrap;
    row-gap: 0;
  }
  .brand {
    gap: 8px;
    flex-shrink: 1;
    min-width: 0;
  }
  .brand-logo {
    width: 28px;
    height: 28px;
  }
  .brand-title {
    font-size: 14px;
    white-space: nowrap;
  }
  .nav-links {
    order: 3;
    width: 100%;
    margin: 4px 0 0;
    gap: 2px;
    padding-bottom: 4px;
  }
  .nav-link {
    padding: 6px 8px;
    font-size: 12px;
  }
  .portal-user {
    gap: 2px;
    margin-left: auto;
  }
  .theme-toggle {
    width: 30px;
    height: 30px;
  }
  .admin-entry {
    padding: 6px 6px;
    font-size: 12px;
  }
  .admin-entry .arrow {
    display: none;
  }
  .nav-center {
    display: none;
  }
}
</style>
