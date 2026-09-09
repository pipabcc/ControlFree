pluginManagement {
    repositories {
        // Google Maven 在当前网络不可直连，使用其只读镜像解析 Android Gradle 插件。
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 当前网络无法直连 Google Maven；镜像仅代理 Google 仓库中的 AndroidX/Google 依赖。
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ControlFree"
include(":app")
