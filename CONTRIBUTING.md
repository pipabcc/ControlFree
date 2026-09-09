# ControlFree 社区贡献指南 (Contributing Guide)

感谢您关注 ControlFree！我们非常欢迎社区开发者参与共同改进与建设。

## 📜 贡献流程

### 1. 提交 Issue
- 在发起 Pull Request 之前，若涉及重大功能重构或新增模块，建议先提交 Issue 进行方案讨论。
- 报告 Bug 时，请详细注明设备机型、Android 系统版本、复现步骤及日志（请确保日志中不含个人敏感信息）。

### 2. 代码规范与分支策略
- 请从 `main` 分支拉取新的特性分支进行开发：
  ```bash
  git checkout -b feat/your-feature-name
  # 或修复 bug 分支
  git checkout -b fix/issue-description
  ```
- 遵循 Kotlin 官方代码风格规范与 Android 架构设计推荐。
- 遵循单一职责原则（SRP），单个提交（Commit）请保持原子性，并编写清晰规范的 Commit Message：
  - `feat: ...` 新增功能
  - `fix: ...` 修复 Bug
  - `docs: ...` 文档修改
  - `style: ...` 格式或 UI 样式微调
  - `refactor: ...` 代码重构
  - `test: ...` 增加或修改测试用例

### 3. 测试与验证
在提交 PR 之前，请务必在本地运行并通过所有单元测试：
```bash
./gradlew test
./gradlew assembleDebug
```

### 4. 发起 Pull Request
- 提交 PR 至 `main` 分支。
- 清晰描述修改原因、改动内容及测试覆盖情况。
- CI 自动化流水线检查通过后，维护者将进行 Code Review 并合入代码。