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
