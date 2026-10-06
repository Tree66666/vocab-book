# 生词错题本（VocabBook）· 开源版

面向英语学习者的「拍照生词错题本」Android 应用。拍摄阅读理解中的生词圈画，AI 自动识别并生成带音标、释义、原文例句的生词卡片，按遗忘曲线（SM-2）安排复习。

**纯单机**：无账号、无云端、数据只存手机本地。AI 识别仅在拍照上传时联网调用，AI 渠道与 API Key 由用户自行填写（OpenCode Go / DeepSeek 官方二选一），模型固定 `deepseek-v4.1-flash`。

## 功能

| 模块 | 能力 |
|---|---|
| 拍照识别 | 拍照 / 相册多选 → 自动压缩 → AI 识别圈画生词（黑笔 / 红笔 / 任意颜色 / 智能选词四模式）→ 每张图独立进度与失败重试 → 卡片结果可编辑 → 确认入库 |
| 生词本 | 搜索、掌握度筛选（陌生 / 已掌握）、考研词频排序、编辑 / 删除、有道查词、一键复核释义 |
| 复习 | 闪卡为主（翻面自动发音，忘记 / 有点印象 / 记得三档评分），SM-2 遗忘曲线调度，间隔满 21 天自动标记「已掌握」 |
| 统计 | 今日新增、累计生词、今日复习、掌握率、掌握度分布、AI token 消耗 |
| 导出 | 生词本 CSV / JSON / Anki / PDF；学习日志 CSV / JSON；一键分享调试日志 |
| 发音 | 优先系统 TTS，不可用时自动联网有道发音（美音 / 英音） |

## 环境要求

- JDK 17
- Android SDK（compileSdk 34 / minSdk 24 / targetSdk 34）
- Gradle 8.5（工程自带 wrapper）

## 安装包

- 可直接安装：`release/vocab-book-v1.0.3.apk`（Android 7.0+，arm64 设备）
- 或自行构建（见下）

## 构建

```bash
# 命令行构建（调试包）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或用 Android Studio 打开本目录，等待 Gradle Sync 后 Build > Build APK(s)。

## 使用说明

1. **首次使用**：「我的 → API 设置」选择渠道（OpenCode Go 或 DeepSeek 官方 API），粘贴你自己的 API Key，保存后点「测试连接」确认可用。Key 仅保存在本机应用私有存储。
2. **拍照识别**：「上传」页拍照或从相册选图（可多选）。识别模式：自动判断（任意颜色圈画）/ 黑笔圈画 / 红笔圈写 / 智能选词（忽略圈画、AI 通读全文挑重要难词）。
3. **生词管理**：识别结果逐词编辑 / 删除后存入生词本；支持有道查词补全、手动录入、考研词频排序。
4. **复习**：「复习」页按遗忘曲线调度到期单词，闪卡模式翻面验证、三档评分。
5. **导出备份**：「我的 → 数据导出」，支持 CSV / JSON / Anki / PDF。

## 架构

```
assets/index.html  ── 前端单页（上传 / 生词本 / 复习 / 统计 / 我的）
        │  WebView + addJavascriptInterface（WordBook 桥）
        ▼
MainActivity ──┬── WordDb.kt     本地 SQLite（words + review_records，SM-2 调度）
               ├── AiClient.kt   AI 识别客户端（OpenCode / DeepSeek 双渠道，Key 动态读取）
               ├── YoudaoClient.kt  有道查词 / 发音兜底
               ├── WordFreq.kt   考研词频（assets/kaoyan.json）
               └── Exporter.kt   导出 CSV / JSON / Anki / PDF
```

## 数据与隐私

- 全部数据（生词、复习记录、设置、API Key）只存本机，无任何云端存储与账号系统
- API Key 仅保存在应用私有 SharedPreferences，不写入代码、不上传
- 识别图片会自动保存到系统相册「生词错题本」目录
- 数据库位于应用私有目录 `wordbook.db`（卸载会清除，请定期导出备份）

## 词频数据

`app/src/main/assets/kaoyan.json`：考研英语 46 套真题句频榜（第三方统计，非官方），含 1199 个高频词及频次，用于生词排序与重要性提示。

## License

[MIT](LICENSE)

> 本项目为个人学习工具，非商业产品；AI 识别结果仅供参考，请结合语境理解单词。
