import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.vt.gitgraph"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val platformLocalPath: String? = providers.gradleProperty("platformLocalPath").orNull
    ?.takeIf { file(it).exists() }

dependencies {
    intellijPlatform {
        if (platformLocalPath != null) {
            local(platformLocalPath)
        } else {
            intellijIdea(providers.gradleProperty("platformVersion"))
        }
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.impl.shared")
        bundledModule("intellij.vcs.git.shared")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        ides {
            if (platformLocalPath != null) local(file(platformLocalPath)) else recommended()
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

tasks.test {
    systemProperty("vt.preview.dir", providers.gradleProperty("previewDir").getOrElse(""))
    systemProperty("vt.preview.repo", providers.gradleProperty("previewRepo").getOrElse(""))
    systemProperty("vt.real.git", providers.gradleProperty("realGitTests").getOrElse("false"))
    outputs.upToDateWhen { !providers.gradleProperty("realGitTests").isPresent }
}
