/**
 * 仓库顺序：官方源优先，国内镜像作为兜底。
 *
 * 之所以不像以前那样"CI 跳过镜像"：一次 CI 撞上 Maven Central 429（Too Many Requests）
 * 会直接让整个 job 红掉；把镜像放到列表末尾，正常情况下用不到，
 * 官方源被限流时能自动回退。
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "typesake-android"
include(":app")
