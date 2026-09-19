pluginManagement {
    repositories {
        val isCi: Boolean =
            System.getenv("CI")?.isNotEmpty() == true || System.getenv("GITHUB_ACTIONS") == "true"
        if (!isCi) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val isCi: Boolean =
            System.getenv("CI")?.isNotEmpty() == true || System.getenv("GITHUB_ACTIONS") == "true"
        if (!isCi) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "typesake-android"
include(":app")
