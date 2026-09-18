// [dsh-android] 流体云状态上报插件（App 每次启动复制到 $DSH_HOME/profiles/web/ 并注入补丁层，勿手改）
// 1) 订阅 agent/status：running = 思考中或跑工具中，idle = 空闲。
//    该事件只在状态**变化**时发，所以无需节流。
// 2) 订阅 api-session/activity：用户在某个会话里发了消息（带会话 id）。
// 3) 让会话目录与工作区清单对 App 可读（见下面的 makeReadable）。
//
// v1.2.30：本文件原本是 EngineConfig.kt 里的 Kotlin 字符串（转义别扭、不好维护），
// 现在挪成 assets 资源；能力桥端口只有 App 知道，用 __DshBridgePort__ 占位，加载时替换。
// v1.2.31：新增"用户当前在用的会话"上报 —— 岛上要显示"你正在弄的那个项目"，
//         光看"哪个会话最近有写入"在两个项目都活跃时会指错（用户实测）。
import { chmodSync, readdirSync } from 'node:fs'

// 【v1.2.28 实测坑】Root 模式下引擎以 uid 0 运行，它新建的会话目录是 0700 root，
// 而 App 界面进程是 uid 10491 —— 读不进去，App 侧 SessionWatcher 只能跳过，
// 于是岛上的「项目名」退化（退回 DSH 或旧项目）。插件跑在引擎进程里（root），
// 把**两级目录**改成 0755 即可：App 只做 list + stat（不读文件内容），
// 所以文件本身保持 0600 不用动，安全面不变（父目录仍是 App 私有的 0700）。
const makeReadable = () => {
  try {
    const root = (process.env.DSH_HOME || '') + '/sessions'
    for (const proj of readdirSync(root)) {
      try { chmodSync(root + '/' + proj, 0o755) } catch (e) {}
      let subs = []
      try { subs = readdirSync(root + '/' + proj) } catch (e) { continue }
      for (const s of subs) {
        try { chmodSync(root + '/' + proj + '/' + s, 0o755) } catch (e) {}
      }
    }
  } catch (e) {}
  // 【v1.2.31】工作区清单（会话 id → 项目名）也要让 App 能读：岛上要拿"你正在用的会话"
  // 去这张表里查项目名。目录本身也得放开，否则 App 连 traverse 都进不去。
  try {
    const home = process.env.DSH_HOME || ''
    chmodSync(home + '/storages', 0o755)
    chmodSync(home + '/storages/workspace.json', 0o644)
  } catch (e) {}
}

export const name = 'dsh-android-fluid-cloud'

export function apply(ctx) {
  const port = __DshBridgePort__
  const post = (body) => {
    fetch('http://127.0.0.1:' + port + '/island', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    }).catch(() => {})
  }
  // 会话 id 是引擎的 SessionId（形如 session-<uuid>）；App 拿它去工作区清单里查所属项目。
  // 只影响岛上的**项目名**，不改岛的状态层。
  const reportSession = (sessionId) => {
    if (typeof sessionId === 'string' && sessionId) post({ action: 'active-session', sessionId })
  }
  ctx.on('agent/status', (payload) => {
    const status = payload && payload.status
    if (status === 'running' || status === 'idle') {
      makeReadable()
      post({ action: 'status', status })
    }
    // payload.agent 由引擎注入（api-session/status 用的就是 agent.id 这个会话 id）
    reportSession(payload && payload.agent && payload.agent.id)
  })
  // 用户在某个会话里发消息 → 引擎发这个事件（第一个参数就是会话 id）：
  // 这是"你此刻在弄哪个项目"最准的信号，比"哪个会话最近有写入"可靠。
  ctx.on('api-session/activity', (sessionId) => {
    reportSession(sessionId)
  })
  // 引擎刚起来时必然空闲：先报一次，免得上一次崩溃前留下的"工作中"卡在岛上
  makeReadable()
  post({ action: 'status', status: 'idle' })
  // 新会话目录随时可能出现（用户开新会话 / 换工作区），比 App 的 5 秒轮询略快即可。
  // unref：退出时不要因为这个定时器拖着进程不走。
  const timer = setInterval(makeReadable, 3000)
  if (timer.unref) timer.unref()
}
