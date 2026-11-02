# AGENTS.md

生词错题本（VocabBook）开源版 Android 应用仓库。前端为 `app/src/main/assets/index.html` 单页，原生层 Kotlin 接 deepseek-v4.1-flash（OpenCode Go / DeepSeek 双渠道，API Key 由用户填写，**禁止写死或提交任何 Key、本机用户名、个人路径、邮箱**）。

## 待办 / 已知问题（下次发版一并处理）

- **双滚动条（v1.0.17 用户反馈）**：屏幕右侧曾出现两个滚动条——v1.0.16 添加的自定义可拖动 `#scrollBar` 与 WebView 原生/系统滚动条叠加显示。修复方向：隐藏原生滚动条（CSS `::-webkit-scrollbar{display:none}` + `scrollbar-width:none`，或 WebView 层 overlay 滚动条设置），保留可拖动的 `#scrollBar` 即可。**状态：v1.0.18 安装后用户确认"又没有了"，疑为 WebView 偶发渲染，暂缓处理；若再出现再修**。
- **策略卡自动触发已落地（v1.0.22）**：触发条件 = 累积 ≥3 次会话 且 样本 ≥5 条 且 距上次更新 ≥7 天（smart 识别完成后静默检查，满足才后台更新并 toast；不满足/失败完全静默只记日志）。手动更新按钮仍在策略弹窗内。
- **UI 调整（v1.0.23）**：识别模式 chips 中「智能选词」放首位且为默认激活模式（state.recogMode 初始 smart）；策略管理面板（状态 + 详情/手动更新/重置）从上传页移至「我的」页卡片，上传页不再显示策略条；检查更新本就在「我的」页。
- **v1.0.24（smart 提示词增强 + 全量审查）**：① PROMPT_SMART 删除"忽略一切圈画标记"，改为圈画/下划线词多判别、同等条件下适当优先但只作参考信号、非最高优先级（前端 hint/使用说明/README 同步）；② 修复识别进行中（有 run 项）点单张重试会覆盖 `window.__aiResult` 导致在途结果丢失、run 项卡死的并发 bug（retryOne 加运行中拦截）；③ removePending 识别中删图时同步移除 recog 队列 wait 项；④ 冗余清理：AiClient 重复类注释、index CSS provider-card 重复块、Manifest 重复存储权限、getStats fuzzy 残留、Exporter dictation 残留、submitReview 无用变量、testApiConn 改显式 event 传参。
- **v1.0.25（对抗式全流程审查 + 4 缺陷修复）**：从用户视角全流程、奇葩操作出发，全量重读 index.html（2032 行）与 8 个 Kotlin 文件，修复：① **recognizeNext 只跳过 ok 不跳过 fail**——单张重试 retryOne 时其他 fail 项被连带重试，且 fail 计数未先减，导致 ok+fail 与 total 不符、进度/汇总失真、再失败 fail 翻倍；while 改为只处理 `status==='wait'`（ok/fail 均跳过），三条路径（retryFails/retryOne/正常串行）已复核自洽；② **doStrategyCall 遇 5xx 不自动重试**——retriable 条件漏"暂时不可用"，补齐（doRecognize/doLookup 本就含，仅策略调用漏）；③ **removePending 删 wait 项后进度卡不立即刷新**——块内补 renderRecogProgress()；④ **Exporter Anki 导出字段未转义**——新增 ankiField() 把字段内 \t/\r/\n 替换为空格，防破坏列结构。评估后保留：识别中全部存入隐藏进度卡（交互边界）、识别中删除+编辑索引并发（极难触发）、onBackPressed canGoBack（单页无历史恒 false）、PDF meta 长行不折行（极端场景）。

## 版本更新必须走完整流程（禁止"半新半旧"）

每次发版，以下步骤**全部完成并验证通过**后才可交付 APK：

1. **改源码**：Kotlin（`app/src/main/java/com/wordbook/app/`）或前端（`app/src/main/assets/index.html`）。
2. **前端静态检查**：div 配平 + 抽取 JS 后 `node --check` + 核对未定义引用/桥方法（跑检查脚本）。
3. **升版本**：`app/build.gradle` 中 versionCode +1、versionName 同步（写 .py 脚本改并回读确认；禁止 PowerShell 内联 `python -c`，会炸）。
4. **构建**：`gradle assembleDebug --no-daemon`（JAVA_HOME / ANDROID_HOME 按会话惯例设置）。
5. **验包**：`aapt2 dump badging` 确认 versionCode/versionName 与目标一致。
6. **产物**：`release/` 只留最新 `vocab-book-vX.Y.Z.apk`；根目录放 `生词错题本-开源版-vX.Y.Z-debug.apk`（先删旧再复制）。
7. **README 同步**：全文检索旧版本号并替换为最新；功能表与实际行为一致。
8. **latest.json**：更新根目录 latest.json 的 version/versionCode/notes/url（url 指向 release/vocab-book-vX.Y.Z.apk 的 raw 地址）——app 的自动检查更新依赖它。
9. **推送**：`git add -A` + commit + `push origin main`（确认输出含 `main -> main`）。
9. **交付**：present_files 交付根目录 APK，附版本变更说明。

## 关键约定

- 每次更新只保留最新版 APK；release/ 与根目录同步清理。
- Key 字段：`api_key_opencode` / `api_key_deepseek`（AES-GCM Keystore 加密）；空 Key 不覆盖；测试连接按渠道建临时 AiClient。
- token 统计：SharedPreferences `ai_tk_<provider>_p/c/r`；识别 max_tokens 16384 / 查词 8192 / 测试 512。
- 拍照上传才存系统相册；相册选图不重复保存。
- 生词本默认排序：hit_count DESC, created_at DESC；复习：`(hit_count + 历史 grade=0 次数) DESC, due_at ASC`（重要难词优先，v1.0.21 起）。
- 策略卡（smart_strategy 表）：AI 输出 JSON 契约（difficulty 四档 / prefer+avoid 带 rule+examples / count 5~20 且 min<max / notes 带数字依据）；校验不过或难度档位相对上版跳变 >1 档直接 throw 拦截；保存时旧卡全部 active=0。
