<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  Collection,
  DataLine,
  Document,
  Finished,
  Menu,
  Setting,
  SwitchButton,
} from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

interface ConsoleNavItem {
  label: string
  section: string
  icon: Component
  to: string | { path: string; query?: Record<string, string> }
  tabs?: string[]
  exactPath?: string
  requiresKb?: boolean
  requiresAdmin?: boolean
}

const kbId = computed(() => (typeof route.params.kbId === 'string' ? route.params.kbId : ''))
const workspacePath = computed(() => (kbId.value ? `/kbs/${kbId.value}` : '/kbs'))
const routeTab = computed(() => String(route.query.tab || 'documents'))
const isGlobalViewer = computed(() => auth.globalRole === 'VIEWER')

const navItems = computed<ConsoleNavItem[]>(() => [
  {
    label: '知识库管理',
    section: 'NAVIGATION',
    icon: Document,
    to: kbId.value ? { path: workspacePath.value, query: { tab: 'documents' } } : '/kbs',
    tabs: ['documents'],
    exactPath: '/kbs',
  },
  {
    label: '知识库问答',
    section: 'NAVIGATION',
    icon: ChatDotRound,
    to: kbId.value ? { path: workspacePath.value, query: { tab: 'chat' } } : '/kbs',
    tabs: ['chat'],
    requiresKb: true,
  },
  {
    label: '协作成员',
    section: 'NAVIGATION',
    icon: Collection,
    to: kbId.value ? { path: workspacePath.value, query: { tab: 'members' } } : '/kbs',
    tabs: ['members'],
    requiresKb: true,
  },
  {
    label: '问答追踪',
    section: 'SYSTEM',
    icon: DataLine,
    to: kbId.value ? { path: workspacePath.value, query: { tab: 'traces' } } : '/kbs',
    tabs: ['traces'],
    requiresKb: true,
  },
  {
    label: '效果评测',
    section: 'SYSTEM',
    icon: Finished,
    to: kbId.value ? { path: workspacePath.value, query: { tab: 'evaluation' } } : '/kbs',
    tabs: ['evaluation'],
    requiresKb: true,
  },
  {
    label: '系统设置',
    section: 'SYSTEM',
    icon: Setting,
    to: '/admin/users',
    exactPath: '/admin/users',
    requiresAdmin: true,
  },
])

const navSections = computed(() => {
  const sections = new Map<string, ConsoleNavItem[]>()
  for (const item of navItems.value) {
    if (!isVisible(item)) {
      continue
    }
    sections.set(item.section, [...(sections.get(item.section) || []), item])
  }
  return Array.from(sections.entries()).map(([label, items]) => ({ label, items }))
})

function isVisible(item: ConsoleNavItem) {
  return !(isGlobalViewer.value && item.section === 'SYSTEM')
}

const pageTitle = computed(() => {
  if (route.path === '/kbs') {
    return '知识库管理'
  }
  if (route.path === '/admin/users') {
    return '系统设置'
  }
  const titles: Record<string, string> = {
    documents: '文档管理',
    chat: '知识库问答',
    members: '协作成员',
    traces: '问答追踪',
    evaluation: '效果评测',
  }
  return titles[routeTab.value] || '工作台'
})

const pageDescription = computed(() => {
  if (route.path === '/kbs') {
    return '选择一个知识库进入工作台，继续完成上传、发布、问答和问答追踪复盘。'
  }
  if (route.path === '/admin/users') {
    return '管理系统用户、全局角色和演示账号权限。'
  }
  const descriptions: Record<string, string> = {
    documents: '集中管理知识库文档：上传、解析、发布索引、预览与检索状态追踪。',
    chat: '围绕当前知识库进行流式问答、多轮追问和引用溯源。',
    members: '管理当前知识库成员角色和协作权限。',
    traces: '复盘问答链路、召回片段、耗时和生成结果。',
    evaluation: '运行基准集评测，查看召回、引用和拒答表现。',
  }
  return descriptions[routeTab.value] || ''
})

const userInitial = computed(() => (auth.username || 'U').slice(0, 1).toUpperCase())
const userRoleClass = computed(() => `role-${(auth.globalRole || 'VIEWER').toLowerCase()}`)

function isActive(item: ConsoleNavItem) {
  if (item.exactPath === '/admin/users' && route.path === '/admin/users') {
    return true
  }
  if (item.exactPath === '/kbs' && route.path === '/kbs') {
    return true
  }
  if (kbId.value && item.tabs?.includes(routeTab.value)) {
    return true
  }
  return false
}

function lockedReason(item: ConsoleNavItem) {
  if (item.requiresKb && !kbId.value) {
    return '请先在知识库列表中选择一个知识库，再进入该功能。'
  }
  if (item.requiresAdmin && auth.globalRole !== 'ADMIN') {
    return '系统设置需要 ADMIN 权限。'
  }
  return ''
}

function isLocked(item: ConsoleNavItem) {
  return Boolean(lockedReason(item))
}

function lockedHint(item: ConsoleNavItem) {
  return item.requiresKb && !kbId.value ? '先选择' : ''
}

async function go(item: ConsoleNavItem) {
  const reason = lockedReason(item)
  if (reason) {
    ElMessage.info(reason)
    return
  }
  await router.push(item.to)
}

async function logout() {
  await auth.logout()
  ElMessage.success('已退出登录')
  await router.push('/login')
}
</script>

<template>
  <main class="page-shell console-shell">
    <aside class="console-sidebar">
      <div class="console-brand">
        <div class="brand-mark">
          <el-icon><Menu /></el-icon>
        </div>
        <div>
          <p>KNOWSOURCE · RAG</p>
          <strong>数据洞察中心</strong>
        </div>
      </div>

      <nav class="side-nav" aria-label="主导航">
        <section v-for="section in navSections" :key="section.label" class="side-nav-section">
          <p>{{ section.label }}</p>
          <button
            v-for="item in section.items"
            :key="item.label"
            class="side-nav-item"
            :class="{ active: isActive(item), locked: isLocked(item) }"
            type="button"
            :aria-disabled="isLocked(item)"
            :title="lockedReason(item) || item.label"
            @click="go(item)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
            <small v-if="lockedHint(item)" class="side-nav-hint">{{ lockedHint(item) }}</small>
          </button>
        </section>
      </nav>
    </aside>

    <section class="console-stage">
      <header class="console-topbar">
        <div class="topbar-title">
          <p class="brand">KNOWSOURCE / {{ pageTitle }}</p>
          <h1>{{ pageTitle }}</h1>
          <small v-if="pageDescription">{{ pageDescription }}</small>
        </div>
        <div class="topbar-actions">
          <div class="user-chip" :class="userRoleClass" :title="`${auth.username} / ${auth.globalRole}`">
            <span>{{ userInitial }}</span>
            <div>
              <strong>{{ auth.username }}</strong>
              <small>{{ auth.globalRole }}</small>
            </div>
          </div>
          <el-tooltip content="退出登录" placement="bottom">
            <el-button :icon="SwitchButton" circle plain @click="logout" />
          </el-tooltip>
        </div>
      </header>
      <slot />
    </section>
  </main>
</template>
