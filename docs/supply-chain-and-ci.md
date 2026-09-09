# 构建门禁与供应链

仓库把依赖解析、构建产物和测试结果纳入同一条 CI 门禁（根目录 `.github/workflows/android-ci.yml`）。合并请求至少需要通过：

- JVM 单元测试 `:app:testDebugUnitTest`
- API 31、35 模拟器 AndroidTest `:app:connectedDebugAndroidTest`
- Release Lint `:app:lintRelease`
- 未签名 Release 构建、Gradle/APK/SBOM 版本一致性检查
- 临时 CI 密钥签名 smoke test、`apksigner verify` 和 SHA-256 产物摘要
- `:app:cyclonedxDirectBom` 生成 CycloneDX 1.6 JSON/XML SBOM
- GitHub Actions 使用完整提交 SHA 固定版本，旁注保留对应的发布 tag 供人工审计

## 依赖变更流程

依赖版本必须先修改 `gradle/libs.versions.toml`，再在干净工作树执行：

```text
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:assembleDebugAndroidTest --write-locks
./gradlew --write-verification-metadata sha256 :app:assembleRelease :app:cyclonedxDirectBom
```

必须提交并人工审核 `app/gradle.lockfile`、`settings-gradle.lockfile`、`gradle/verification-metadata.xml`，以及未来新增模块产生的锁文件。校验摘要缺失或不匹配时，`org.gradle.dependency.verification=strict` 会让构建立即失败；禁止用 `--dependency-verification=off` 绕过 CI。

SBOM 默认只分析 `releaseRuntimeClasspath`，输出到 `app/build/reports/cyclonedx-direct/`。升级 CycloneDX 插件本身也必须更新版本目录和校验元数据。

CI 使用短期、仅用于验证的临时密钥，不代表正式发布签名。正式发布仍应在受控签名环境完成，并单独保管密钥材料。
