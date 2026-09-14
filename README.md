# ControlFree

<p align="center">
  <strong>一款开源、完全零广告、无后台隐私窃取的 Android 屏幕时间管理与专注自律应用</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL%20v3-blue.svg" alt="License: AGPL v3"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.0+-purple.svg" alt="Kotlin"></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose-green.svg" alt="Jetpack Compose"></a>
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen.svg" alt="Android">
</p>

---

ControlFree 基于最新的现代 Android 技术栈与响应式界面开发，致力于帮助用户抵御数字成瘾、科学规划日常精力，重塑高效自律的生活节奏。

## 🌟 特色功能 (Key Features)

### 1. 🛡️ 应用监督计划 (App Supervision & Blocker)
* **应用时长限制**：自定义限制特定应用或应用分类的每日允许使用时长。
* **强力时间阻断**：当使用时长超限时，自动拉起全屏拦截层，阻止继续沉迷（严密覆盖前后台状态切换）。
* **多计划与时间段**：支持针对不同工作日、休息日或特定时间段设定监督策略。

### 2. 🌱 趣味番茄专注与成长体系 (Gamified Focus Pomodoro)
* **游戏化专注培育**：内置植物成长系统。每次完成专注番茄钟，即可获得水源，浇灌并培育专注植物。
* **专注白噪音**：专注期间可伴随白噪音，帮助心境快速归于宁静。

### 3. 📝 自律清单计划 (Minimalist Todo & Task Planner)
* **轻量待办看板**：简洁直观的 Todo 看板与四象限/时间清单，合理规划每日学习与工作任务。
* **专注与任务闭环**：支持针对特定待办任务直接发起番茄专注，沉淀每次专注成果。

### 4. 📊 深度屏幕时间分析与统计 (App Usage Analytics)
* **应用使用排行**：全天候精准记录每个应用的具体运行时长与启动频次。
* **专注趋势曲线**：图表化展示每日专注时长、植物成长记录与习惯养成曲线。

### 5. 🔐 硬件级隐私与安全设计 (Android KeyStore Encryption)
* **API 密钥硬件加密**：支持集成 AI 能力（如 DeepSeek API 进行智能自律督导）。所有用户 API Key 均采用 Android 底层硬件级 **Android KeyStore** (AES-GCM-256) 非对称/对称加密体系，零明文存储，充分守护密钥安全。

---

## 🛠️ 技术栈与架构 (Tech Stack)

* **开发语言**：[Kotlin](https://kotlinlang.org/)
* **UI 框架**：[Jetpack Compose](https://developer.android.com/jetpack/compose) (全声明式现代 UI)
* **异步与数据流**：Kotlin Coroutines & Flow
* **架构模式**：MVVM (Model-View-ViewModel)
* **持久化**：Jetpack DataStore / SharedPreferences / Room
* **加密与安全**：Android KeyStore 加密体系

---

## 🚀 开发者编译指南 (Build & Run)

### 1. 克隆代码库
```bash
git clone https://github.com/pipabcc/ControlFree.git
cd ControlFree
```

### 2. 打开与环境准备
* 启动 **Android Studio** (推荐 Hedgehog 或更高版本)。
* 选择 **Open**，直接指向本项目根目录。
* **JDK 版本**：Java 17 (推荐 Temurin JDK 17 或 Android Studio 自带 JDK 17)。
* **Android SDK**：API Level 33+。

### 3. 编译构建
在项目根目录下通过 Gradle Wrapper 构建 Debug APK：
```bash
./gradlew assembleDebug
```
构建成功后，生成的 Debug APK 位于：
`app/build/outputs/apk/debug/app-debug.apk`

> ⚠️ **关于密钥与签名：**
> 本地 Debug 构建采用 Android 官方默认 Debug 签名。Release 构建默认生成未签名 APK；如需签名，请在本地创建未被 Git 跟踪的 `gradle-local.properties`，或设置 `CF_RELEASE_STORE_FILE`、`CF_RELEASE_STORE_PASSWORD`、`CF_RELEASE_KEY_ALIAS`、`CF_RELEASE_KEY_PASSWORD` 环境变量。正式发布签名材料属于私密凭据，绝不可提交到公共代码库。

### DeepSeek API（可选）

应用的 AI 督导功能需要用户自行提供 DeepSeek API Key。密钥只在设备端通过 Android KeyStore 加密保存，项目不会内置或上传任何密钥。未配置 API Key 时，其余本地功能仍可正常使用。

---

## 🤝 参与贡献 (Contributing)

欢迎任何形式的贡献！在提交 Issue 或发起 Pull Request 之前，请阅读我们的 [贡献指南 (CONTRIBUTING.md)](CONTRIBUTING.md)。

---

## 🔖 推荐 GitHub 标签 (GitHub Topics)

发布到 GitHub 时推荐添加以下标签：
`android` · `kotlin` · `jetpack-compose` · `productivity` · `app-blocker` · `focus-timer` · `screentime` · `self-discipline` · `gamification` · `anti-addiction` · `todo-list` · `deepseek-api`

---

## 📄 开源许可证 (License)

本项目采用 [GNU AGPLv3](LICENSE) 许可证开源。当您对本项目进行修改、二次分发或作为网络服务运行时，必须完整保留原作者声明并开源派生源码。
