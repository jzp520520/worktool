# RoomType 内外判定修复：所有群被识别成 3

> 2026-08-08。线上所有群(含外部群)一律上报 roomType=3(内部群)，外部群识别形同虚设。
> 本补丁改 `WeworkRoomUtil.kt`，需 review 后打进 APK 装机验证。

## 根因（源码级）

判定本体 `WeworkRoomUtil.getRoomType()`，按顺序：
```
① isExternalSingleChat()  → 2 外部联系人 (标题含 @ 开头)
② isExternalGroup()       → 1 外部群
③ isGroupChat()           → 3 内部群 (群名带 (N) 人数)
④ isSingleChat()          → 4 内部联系人
⑤ else → 0
```

**逻辑顺序本身是对的**——外部群判定(②)在内部群判定(③)之前，只要②能识别"外部"标记，外部群会先命中返回 1。
问题全在 ② 的三个缺陷，导致它**必然失败**，于是所有群落到③「群名带人数→内部群」。

旧代码：
```kotlin
val listView = findOnceByClazz(getRoot(), ListView, limitDepth=null, depth=0)
val frontNode = findFrontNode(listView)          // ① 直接前兄弟
val nodeList = findAllOnceByText(frontNode, "外部群")   // ② 搜"外部群"三字
```

- **缺陷A(关键词错)**：企微界面标记是**「外部」两字**（列表页群名右侧、聊天页群名下方），代码搜**「外部群」三字** → 必然搜不到。
- **缺陷B(区域错)**：`findFrontNode(listView)` 只找消息列表的**直接前兄弟**，聊天窗口层级稍复杂就 miss 或找到无关节点；而 `getRoomTitle()` 用的是 `findFrontNode(listView.parent?.parent)`（已验证能拿到群名），两处定位还不一致。
- **缺陷C(漏desc)**：`findAllOnceByText` 默认只搜 `node.text`，不搜 `contentDescription`。「外部」标注用户描述"不能读取复制"，极可能承载在 contentDescription 里 → text 搜永远 miss。

三个缺陷叠加 → ②必失败 → ③「(N)人数」兜底接住所有群。**(N) 是内外群都有的，它只能区分「群 vs 单聊」，不能区分内外**——它被冤枉了，根因在 ②。

## 改动（WeworkRoomUtil.kt，29+/6-）

1. **`isExternalGroup()` 重写**：
   - 关键词 `"外部群"` → `"外部"`（含独立TextView精确匹配、与群名同节点contains、contentDescription三路）。
   - 区域定位与 `getRoomTitle` 对齐：`findFrontNode(listView.parent?.parent) ?: findFrontNode(listView)`。
   - 全树精确匹配兜底（排除消息列表子树内命中，防消息内容误判）。
2. **`isGroupChat()` 注释**：明确 (N) 只区分群/单聊，内外靠 ② 拦截。
3. 新增私有辅助 `isExternalMark(node)`。

## 装机验证（重点）

**期望**：worktool测试(外部群) 上报 roomType 从 3 → 1。

服务器侧，`message_processor.py` 的原始 APP 值日志（在 BUG#4 DB 覆盖之前）：
```
[Processor] 收到数据: roomType=1, atMe=..., groupName=worktool测试...
```
- 若出现 `roomType=1` → 修复生效，外部群识别恢复。
- 若仍全 `roomType=3` → 说明「外部」标记不在无障碍树里(纯图像渲染)，需上 Plan B。

APP 本地日志另有分支日志：`ROOM_TYPE: 识别到外部群标记「外部」(标题区域)` / `(全树精确)`。

## Plan B（若标记读不到）：数据驱动兜底

群里消息出现**外部联系人 sender**（企微外部成员发言 sender 名字/旁带 `@微信`）→ 该群必为外部群（群性质创建时定死）。在 `getChatMessageList` 收到消息时检测 sender 特征并覆盖 roomType=1。此为后续方案，先验证本补丁。

---

# 群成员读取补丁（GET_GROUP_INFO 501）

> 2026-08-08。目标：单群读取全部群成员（进群→三竖点→查看全部群成员→分屏下划）。

## 现状（原代码已有逻辑，但两处断裂）

- 读取本体 `WeworkGetImpl.getGroupInfoDetail(saveMembers)` **已完整**：读群名/群主/成员数/公告/备注；`saveMembers=true` 且群>8人时点击「查看全部群成员」→ ListView 分屏 + `scrollToBottom(maxRetry=100)` 下划读全。
- **断裂1**：501 `getGroupInfo` 调 `getGroupInfoDetail()` 默认 `saveMembers=false` → 大群(>8人) nameList 不返回。
- **断裂2**：501 `getGroupInfo` **从不调 `uploadCommandResult`** → 无 socketType=3 回包 → server `pendingRequests` 挂 60s 超时（此前「APP 60s不回」根因）；且成员走 groupInfo(socketType=2 type=501) 上报，server 只 log 不返回给 HTTP。

## 改动（WeworkGetImpl.kt `getGroupInfo`）

1. `getGroupInfoDetail(saveMembers = true)` → 单群也分屏下划读全部成员。
2. 读到的 `nameList` 经 `uploadCommandResult(... successList = nameList)` 回传：
   - socketType=3，`messageId` = 指令 messageId → server `pendingRequests` 按 messageId 匹配 → **sendRawMessage HTTP 同步响应直接返回成员**。
   - 进群失败也回传 errorCode（不再静默超时）。
   - 多群 selectList 时上报最后一个成功读到的群成员（一次回包）。

## SaaS 侧配合（groups.py `sync_group_members`）

- payload 改 `{socketType:2, list:[{type:501, groupName, selectList:[groupName]}]}`。
- **删掉等 webhook 回调的轮询**（worktool-server 故意不转发指令结果），改从 sendRawMessage HTTP 同步响应 `data.list[0].successList` 拿成员。
- httpx timeout 65s（> server 60s）。

## 装机验证

1. 新版 APK 装机后，SaaS 调 `/api/groups/{id}/sync-members`。
2. 服务器日志看 `[Sync] 发送请求: payload=...` 和 `WorkTool get_group_members response: ...`。
3. 成功 → response `data.list[0].successList` 含全部成员名，DB `group_members` 落库。
4. 若 response `code=408`（超时）→ APP 无障碍未完成进群/滚动（PC 企微需可导航状态，同 201102 问题）。

## 编译状态（2026-08-08）

- **本地工具链**：JDK 11（`/c/Program Files/Java/jdk-11.0.25+9`，Gradle 6.1.1/AGP 4.0 不兼容 JDK 17）+ Android SDK（`C:/Android/sdk`，platform-tools/platforms;android-30/build-tools;30.0.3）。
- **坑1**：`local.properties` 的 `sdk.dir` 用**正斜杠** `C:/Android/sdk`；反斜杠 `C:\Android\sdk` 会被 properties 转义成 `C:Android\sdk` → SdkLocator 报「卷标语法不正确」。
- **坑2**：`AccessibilityNodeInfo.isAncestorOf` 是 **API 31+** 方法，compileSdk 30 编译不过 → 改手动父链遍历 `isDescendantOf()`（兼容 minSdk 24）。
- **产物**：`assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`（11MB，debug 版保留 LogUtils.d 诊断日志，适合装机验证 roomType 分支）。release 版 R8 会剥 Log，如需生产替换可另出签名 release。

