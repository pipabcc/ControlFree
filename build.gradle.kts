// Top-level build file where you can add configuration options common to all sub-projects/modules.
import org.gradle.api.artifacts.dsl.LockMode

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.cyclonedx.bom) apply false
}

// 所有可解析配置都必须使用提交到版本库的锁文件。
// 依赖升级通过显式的 --update-locks 操作完成，避免构建时静默漂移。
allprojects {
  dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
  }
}
