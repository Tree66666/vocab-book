# AGENTS.md

生词错题本（VocabBook）开源版 Android 应用仓库。前端为 `app/src/main/assets/index.html` 单页，原生层 Kotlin 接 deepseek-v4.1-flash（OpenCode Go / DeepSeek 双渠道，API Key 由用户填写，**禁止写死或提交任何 Key、本机用户名、个人路径、邮箱**）。

## 待办 / 已知问题（下次发版一并处理）

- **闪卡自测流程改造（2026-10-29 用户提出，先记录）**：用户设想的三阶段闪卡交互：① 正面仅显示单词；② 给「记得 / 不记得」两个按钮——点「记得」直接跳到释义页；点「不记得」先弹例句作提示（不给释义），再选一次「记得 / 不记得 / 有印象」，然后才跳释义页。当前流程是"点卡片直接翻面看释义再评分"，改造后更贴合主动回忆学习法。实现方向：`showCard` 正面增加两按钮并区分初判/复判状态；例句提示用独立步骤（可复用 `rsent` 样式）；跳释义页后按最终评分调 `grade()`，注意与"忘记的词当天重看"逻辑（v1.0.17）兼容。
- **背诵卡显示词汇重要程度（2026-10-29 用户提出，先记录）**：复习闪卡（背诵卡）正面/背面加考研词频重要程度展示，与生词本一致（`getFreqs` → 「🎯 label + 大纲内/超纲」）。实现方向：`loadReview` 拿到 due 后批量 `WordBook.getFreqs` 存 `state.reviewFreq`，`showCard`/`showFlashBack` 在 `rsub` 下复用 `.freqline/.freqtag/.f-un` 样式渲染词频行。
- **双滚动条（v1.0.17 用户反馈）**：屏幕右侧曾出现两个滚动条——v1.0.16 添加的自定义可拖动 `#scrollBar` 与 WebView 原生/系统滚动条叠加显示。修复方向：隐藏原生滚动条（CSS `::-webkit-scrollbar{display:none}` + `scrollbar-width:none`，或 WebView 层 overlay 滚动条设置），保留可拖动的 `#scrollBar` 即可。**状态：v1.0.18 安装后用户确认"又没有了"，疑为 WebView 偶发渲染，暂缓处理；若再出现再修**。

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
- 生词本默认排序：hit_count DESC, created_at DESC；复习：due_at ASC, hit_count DESC。
