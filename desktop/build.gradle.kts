import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

val appBuildGradle = rootProject.file("app/build.gradle.kts").readText()
val appVersionName: String = Regex("""versionName\s*=\s*"([^"]+)"""")
    .find(appBuildGradle)?.groupValues?.get(1) ?: "1.0.0"
val numericPackageVersion: String = appVersionName.substringBefore('-')
    .split('.')
    .take(3)
    .let { parts ->
        val nums = parts.map { it.toIntOrNull() ?: 0 }.toMutableList()
        while (nums.size < 3) nums.add(0)
        "${nums[0]}.${nums[1]}.${nums[2]}"
    }

val generateBuildInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/source/buildinfo")
    inputs.property("versionName", appVersionName)
    outputs.dir(outDir)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("ru/nstu/mail/desktop")
        pkgDir.mkdirs()
        File(pkgDir, "DesktopBuildInfo.kt").writeText(
            """
            package ru.nstu.mail.desktop

            object DesktopBuildInfo {
                const val VERSION_NAME = "$appVersionName"
                const val GITHUB_REPO = "SnipDev1/NstuMailApk"
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets.main {
    kotlin.srcDir(generateBuildInfo.map { layout.buildDirectory.dir("generated/source/buildinfo") })
}

val runtimeAttrs = configurations.getByName("runtimeClasspath").attributes
val windowsRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        runtimeAttrs.keySet().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            attribute(key as Attribute<Any>, runtimeAttrs.getAttribute(key)!!)
        }
    }
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-linux-x64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-linux-arm64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-macos-x64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-macos-arm64")
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")

    windowsRuntime(project(":core"))
    windowsRuntime("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.7.1")
    windowsRuntime("org.jetbrains.compose.material3:material3-desktop:1.7.1")
    windowsRuntime("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.1")
    windowsRuntime("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    windowsRuntime("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
}

val packageWindowsUberJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "Package a Windows x64 fat JAR including Skiko Windows natives"
    archiveFileName.set("NstuMail-windows-uber.jar")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true

    dependsOn(tasks.named("classes"))
    from(sourceSets.main.get().output)
    from({
        windowsRuntime.resolve().map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
        exclude("META-INF/versions/*/module-info.class", "module-info.class")
    }
    manifest {
        attributes["Main-Class"] = "ru.nstu.mail.desktop.MainKt"
    }
}

compose.desktop {
    application {
        mainClass = "ru.nstu.mail.desktop.MainKt"

        buildTypes {
            release {
                proguard {
                    isEnabled.set(false)
                }
            }
        }

        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "NstuMail"
            packageVersion = numericPackageVersion
            description = "Почта НГТУ — настольный почтовый клиент"
            vendor = "NSTU Mail Client"

            modules(
                "java.instrument",
                "java.logging",
                "java.naming",
                "java.prefs",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.unsupported"
            )

            linux {
                iconFile.set(project.file("src/main/resources/nstumail.png"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/nstumail.ico"))
            }
        }
    }
}
