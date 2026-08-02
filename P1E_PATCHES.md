# P1-E: worktool APP 去重/漏消息补丁

> 2026-07-26。仓库无法在此环境 build/测试(无 Android 工具链)，以下补丁需你 review 后打进 APK 验证。
> 目标痛点：**重复回复** + **漏消息**（诊断：主要是本 APP）。

## 已应用（安全、零语法风险）

### Patch 1 — init() 不再清除去重/水位 SP（修「漏消息」+「重复告警」）
`app/src/main/java/org/yameida/worktool/service/MyLooper.kt` `init()`

**根因**：`init()` 在每次无障碍服务重连(`WeworkService.onServiceConnected`)都跑，原先清空 7 个 SP，其中 5 个是跨重连必须保留的状态：
- `lastSyncMessage` 收消息**水位游标** —— 清了 → `checkNoSyncMessage` 跳过房间 → 「红点消失但服务器没收到」的消息不再补救上报 = **漏消息**
- `noSyncMessage` 不一致告警去重 —— 清了 → 同一条反复 `error()` + 进房间
- `noTipMessage` 系统消息 1h 限频 —— 清了 → 同一条反复点击/上报
- `groupInvite` 群邀请幂等 —— 清了 → 对同一邀请二次点击
- `lastImage` 图片去重 —— 清了 → 重复推图

**改法**：只保留 `limit`/`myInfo` 两个纯运行时缓存的 clear，去掉上述 5 个。

### Patch 3a — switchCorp 不再永久关闭去重（修「切企后重复回复」）
`WeworkOperationImpl.kt` `switchCorp()` 原先 `Constant.duplicationFilter = false` 但**从不恢复** → 切过一次企后，`MyLooper` 的批内(LinkedHashSet)+队列(removeMessages)去重**永久失效**。
**改法**：删除该行（函数体不依赖此标志，去重保持常开）。

---

## 误报澄清

Explore agent 曾报「`WeworkMessageBean` 无 equals/hashCode → `MyLooper:111` LinkedHashSet 去重 no-op」。
**经核实是错的**：`WeworkMessageBean.java:415-426` 已有完整的字段级 equals/hashCode，批内去重**是生效的**。无需补 equals/hashCode。

---

## Patch 3c — 入站整批重放 → 增量上报水位（2026-07-31 E2E 实锤后新增）

**背景**：Patch 1+3a 部署前 E2E 确认**跨会话整批重放仍在生产发生**（20:14:43 一秒内把上一轮被杀运行的旧消息 `#AI`/`查客户`/`去重测试` 整批重发 → 消息乱序回复错配 + 旧消息占用发送锁 → 真消息漏回）。根因=路径A：`getChatMessageList` 每次进房间**整列表上报，本地无已上报去重集合**；服务端 300s 去重窗口只拦同窗口重放，跨窗口(重连/被杀重进/3s 递归)拦不住。

**改法**（`WeworkLoopImpl.kt`）：
- 新增 `incrementalReport(titleList, messageList)`：持久化水位 `SP lastReportCount[房间标题]=上次上报后消息总条数`。
  - 条数增长 → 只上报水位之后的新消息（纯增量）
  - 条数相同 → 返回空列表跳过发送（不重放）
  - 列表收缩/水位失效/首次 → 全量兜底（服务端去重拦重复，不可漏）
- 发送块改用 `reportList`，空则跳过 `send`。
- 水位用**条数**而非文本，避开 lastSyncMessage 的名称振荡（路径B）；`needInfer` 递归的第二次上报现在只发新增（AI 回复），不再整批重发。

**注意**：首次装机无水位 → 仍会全量上报一次（预期，服务端去重接住）；跑一会儿后水位生效，后续全增量。

---

## Patch 3d — 检测逻辑加固, 修上报延迟/漏检（2026-08-01 E2E 实锤后新增）

**背景**：2026-08-01 DB 全量核实铁证——PC 客户端发送一直可靠、APP 从不丢消息、从不整批重放，**唯一问题 = APP 上报延迟(数秒~5min 不定、乱序)**。根因在首页检测启发式漏检。修改 `WeworkLoopImpl.kt`：

- **`checkNoSyncMessage`**：
  - 新增 `isPreviewSynced()`：预览与 lastSyncMessage **剥离发送者前缀后完全相等**才算已同步。替代旧的 `contains` 子串判断（新消息预览恰好包含旧消息文本时被误判已同步 → 漏检延迟）。
  - 时间格式无法识别(如纯日期)不再 `return -1` 整表早退 → 跳过该房间继续扫其它房间（原逻辑一个房间时间格式不认识, 整个首页同步检查就失效）。
  - `noSyncMessage` 守卫加 **30s 冷却重试**：同一 lastSyncMessage 不一致时 30s 后可重进（原逻辑永久抑制到周期巡检才恢复）。
  - 时间正则补 `今天/小时前/月/日`。
- **`getChatroomList`** 红点检测 `childCount==4||5` → `in 3..6` 放宽（a11y 树结构变化不漏检）。

**权衡**：检测更激进 → 同一消息可能多报(预览截断等), 服务端 60s 去重窗口接住, 代价可接受。**需装机验证上报延迟是否从"数分钟"降到"秒级"。**

---

## Patch 3e（2026-08-02，commit 见下）— isAtHome 与条数水位一致性修复

**背景**：Patch 3d 装机后 probe 显示「首条上报正常, 之后全静默」。日志/命令通道实锤：**机器人主循环完全没在跑首页扫描**（150s 零日志），但 WS 心跳与发送指令正常。

**根因一 — `isAtHome()` 未随 3d 放宽（主因，修复检测全死）**：
- Patch 3d 把 `getChatroomList` 红点检测 `childCount==4||5` → `in 3..6`，但 **`isAtHome()`(GlobalMethod.kt)、`goHomeTab`、`getFriendRequest`、深扫、`hasNewMessage` 共 5 处仍用旧 `4||5`**。
- 这台企微版本「消息」tab 无红点时 a11y 结构 childCount 是 3 或 6 → `isAtHome()` 恒 false → 主循环死磕 `if(!isAtHome())` 分支：`getChatMessageList`(首页 roomType=UNKNOWN 静默早退)+`goHome()` 空转 → **`getChatroomList` 永不执行, 永不检测新消息**。WS/发送队列是独立机制故仍正常。
- 修复：5 处全部 `childCount in 3..6`。

**根因二 — 条数水位滑动窗口漏报（`incrementalReport`）**：a11y 聊天列表是滑动窗口, 新消息进来通常「位移」而非「增长」(条数不变) → 条数相等被判「无新增」整批跳过。实测 -2 在群里、机器人进房 `getChatMessageList` 也不报。
- 修复：水位改存「上次上报的最后一条消息文本」(SP `lastReportMsg[room]`)，比对当前最后一条：相同→无新增；不同→从末尾找上次位置上报其后全部；找不到/首次→全量(服务端去重兜底)。

**验证**：装机后 re-probe，2 分钟内新消息应秒级入站；worktool-server `LOG_LEVEL=debug` 日志可见「读取首页聊天列表」每 ~120s 出现、红点检测、增量上报等全链路。

---

## 待你决策的可选增强（Patch 3b — 终端发送幂等）

**场景**：`MyLooper.kt:42-55` `handleMessage` 捕获异常后会 `goHome()` + **重试同一条** `dealWithMessage`。若 SEND 类指令第一次已「输入文本+点了发送」后才抛异常，重试会**再发一次 = 重复回复**。服务端 P0-3（`OutboundSend` 幂等，表已建）挡住了「服务端下发两次」，但挡不住「APP 把一条执行两次」。

**设计**（SP 持久化已执行指令，终端发送前查重）：
1. 新建 `ExecutedCommandCache`（object），SP 存最近 N 条(如 500，FIFO 裁剪)已执行 SEND 指令的 key。
2. key = `${message.messageId}#${message.type}#${message.titleList?.join()}#${message.receivedContent}`
   （`messageId` 已在 `MyLooper.kt:132/146` 注入；同一帧兄弟指令共享 messageId，故必须拼 type+title+content 区分单条。**不要用 `generateFeatureValue`** —— 它是帧级 MD5，粒度错、会碰撞。）
3. 在 `WeworkController.sendMessage`/`replyMessage`（SEND 类入口）最前面：若 key 已在缓存 → `uploadCommandResult(... SUCCESS "幂等跳过")` 直接返回，不执行；否则执行成功后 `put(key)`。

**为什么是可选**：① 需 Kotlin 新文件 + 集成，本环境无法 build 验证；② P0-3（服务端）已覆盖最常见的「重复下发」场景，APP 侧重试双发是窄面。建议先发布 Patch 1+3a 观察重复率，仍有问题再上 3b。

---

## 注意

- 这两个已应用补丁**改的是删除/注释行**，不影响 Kotlin 编译，可直接 build。
- `Constant.duplicationFilter` 默认 ON（`Constant.kt` `getBoolean("apiDuplicationFilter", true)`），Patch 3a 后保持常开。
- 服务端侧的配套：P0-2(入站持久去重)/P0-3(外发幂等) 已生效（`outbound_send`/`processed_msg_keys` 表已建，此前一直缺失=降级失效）。
