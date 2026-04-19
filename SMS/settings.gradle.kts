pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sms-link"

include(":app")

// Core modules
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:preferences")

// Network modules
include(":network:protocol")
include(":network:transport")
include(":network:discovery")
include(":network:hotspot")

// Feature modules
include(":feature:device")
include(":feature:notification")
include(":feature:call")
include(":feature:transfer")
include(":feature:settings")

// Audio module
include(":audio")

// UI module
include(":ui")
