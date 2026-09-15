import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.ksp)
  alias(libs.plugins.cyclonedx.bom)
}

// 签名凭据不入库：优先读 gradle-local.properties（已被 .gitignore 排除），
// 其次读环境变量。两者都缺失时 release 构建退化为 unsigned，不再报错。
val signingProperties = Properties().apply {
  val localFile = rootProject.file("gradle-local.properties")
  if (localFile.exists()) localFile.inputStream().use(::load)
}

fun signingCredential(key: String, envKey: String): String? =
  signingProperties.getProperty(key) ?: System.getenv(envKey)

android {
    namespace = "com.example.controlfree"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.controlfree"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "3.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingCredential("cf.release.storeFile", "CF_RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                // 相对路径按项目根目录解析（与旧配置 rootDir/../mykey.keystore 一致）
                storeFile = rootProject.file(storeFilePath)
                storePassword = signingCredential("cf.release.storePassword", "CF_RELEASE_STORE_PASSWORD")
                keyAlias = signingCredential("cf.release.keyAlias", "CF_RELEASE_KEY_ALIAS")
                keyPassword = signingCredential("cf.release.keyPassword", "CF_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    lint {
      checkReleaseBuilds = false
      abortOnError = false
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// CycloneDX 以 Project.version 作为 SBOM 根组件版本，绑定到唯一的 Android 版本声明。
version = requireNotNull(android.defaultConfig.versionName) {
    "defaultConfig.versionName must be declared"
}

kotlin {
    jvmToolchain(17)
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.generateKotlin", "true")
}

// SBOM 仅纳入发布运行时依赖，避免把测试、Lint 和 KSP 的工具链重复计入交付清单。
// 任务由 CI 调用：./gradlew :app:cyclonedxDirectBom
tasks.named("cyclonedxDirectBom") {
  setProperty("includeConfigs", listOf("releaseRuntimeClasspath"))
  setProperty("projectType", "application")
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.compose.material:material-icons-extended")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.kotlinx.coroutines.test.android)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.room.testing)
}
