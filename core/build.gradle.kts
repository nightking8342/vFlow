import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import javax.inject.Inject

abstract class GenerateCoreBuildInfoTask : DefaultTask() {
    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val versionName: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val targetFile = outputFile.get().asFile
        targetFile.parentFile.mkdirs()
        targetFile.writeText(
            """
            package com.chaomixian.vflow.server.common

            object CoreBuildInfo {
                const val VERSION_CODE = ${versionCode.get()}
                const val VERSION_NAME = "${versionName.get()}"
            }
            """.trimIndent()
        )
    }
}

abstract class BuildDexTask : DefaultTask() {
    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    abstract val androidJar: RegularFileProperty

    @get:Input
    abstract val javaExecutablePath: Property<String>

    @get:Classpath
    abstract val r8Classpath: ConfigurableFileCollection

    @get:Input
    abstract val coreVersion: Property<Int>

    @get:OutputDirectory
    abstract val tempDexDir: DirectoryProperty

    @get:OutputFile
    abstract val targetDex: RegularFileProperty

    @get:OutputFile
    abstract val targetVersionFile: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun buildDex() {
        val tempDex = tempDexDir.get().asFile
        val targetDexFile = targetDex.get().asFile
        val targetVersion = targetVersionFile.get().asFile
        val androidJarFile = androidJar.get().asFile

        tempDex.mkdirs()
        targetDexFile.parentFile.mkdirs()

        execOperations.exec {
            commandLine(
                javaExecutablePath.get(),
                "-cp", r8Classpath.singleFile.absolutePath,
                "com.android.tools.r8.D8",
                "--lib", androidJarFile.absolutePath,
                "--output", tempDex.absolutePath,
                inputJar.get().asFile.absolutePath
            )
        }

        val generatedDex = tempDex.resolve("classes.dex")
        if (!generatedDex.exists()) {
            throw GradleException("d8 命令执行失败，未生成 classes.dex")
        }

        if (targetDexFile.exists()) {
            targetDexFile.delete()
        }
        generatedDex.copyTo(targetDexFile)
        targetVersion.writeText(coreVersion.get().toString())

        println("✅ Server Dex 构建成功并已复制到: ${targetDexFile.absolutePath}")
        println("📊 DEX 大小: ${targetDexFile.length() / 1024} KB")
        println("🧩 Core 版本: ${targetVersion.readText().trim()}")
    }
}

plugins {
    id("java-library")
    kotlin("jvm") // 使用标准 Kotlin JVM 插件
}

/**
 * Core 的**对外**版本号。
 *
 * ## 什么时候该改它
 *
 * **只在功能稳定、准备发版时改。** 日常开发改了 `core/src` **不需要动它**。
 *
 * ## 那"改了 core 但没重启，跑的还是旧代码"怎么办
 *
 * 由**另一套机制**解决，与这个版本号无关：
 * `VFlowCoreBridge.isCoreDexNewerThanRunning()` 比较「apk 里的 dex 指纹」
 * 与「上次启动 Core 时用的指纹」，不一致就在 Core 管理页提示重启。
 *
 * 用指纹而非版本号的原因：两者的**节奏不同** ——
 * "改了 core 要重启"是开发期的高频需求，而版本号只在发版时动。
 * 用版本号当判据的话，要么频繁改、要么一直忘改（实际就忘过两次）。
 *
 * ## ⚠️ 注意它**不能**自动触发重启
 *
 * `getCoreVersionStatus().needsUpdate` 只被两处 UI 用到（首页与管理页的提示），
 * 而 `MainActivity.checkCoreAutoStart()` 只判断"Core 活没活"，不看版本。
 * 也就是说：**改了版本号也不会让旧进程自动重启，仍然需要手动重启一次。**
 *
 * 19 → 20 → 22 这段历史是早期误用版本号担当"需要重启"信号留下的，
 * 现在职责已移交指纹机制，此处不再需要跟着每次改动递增。
 */
val vflowCoreVersion = 22

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
val sdkDir = localProperties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
checkNotNull(sdkDir) { "未找到 Android SDK 路径，请在 local.properties 中设置 sdk.dir" }

// 指定编译用的 android.jar (仅用于存根，不打包)
val androidJar = "$sdkDir/platforms/android-36/android.jar"

val currentJavaExecutablePath = System.getProperty("java.home") + "/bin/java"
val r8Version = providers.gradleProperty("android.tools.r8.version")
    .orElse("8.13.19")

val r8Configuration = configurations.create("r8")

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val generatedCoreBuildInfoDir = layout.buildDirectory.dir("generated/sources/coreBuildInfo/kotlin")
val generatedCoreBuildInfoFile = layout.buildDirectory.file(
    "generated/sources/coreBuildInfo/kotlin/com/chaomixian/vflow/server/common/CoreBuildInfo.kt"
)
val androidJarFile = file(androidJar)

sourceSets {
    main {
        java.srcDir(generatedCoreBuildInfoDir)
    }
}

val generateCoreBuildInfo by tasks.registering(GenerateCoreBuildInfoTask::class) {
    versionCode.set(vflowCoreVersion)
    versionName.set(vflowCoreVersion.toString())
    outputFile.set(generatedCoreBuildInfoFile)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    dependsOn(generateCoreBuildInfo)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

dependencies {
    // 仅编译时依赖 android.jar (运行时由系统提供)
    compileOnly(files(androidJar))

    // JSON 解析库 (运行时需要，会被打入 dex)
    implementation("org.json:json:20251224")

    // 单元测试。core 是纯 JVM 模块（java-library + kotlin jvm），
    // 因此可以直接放 src/test 跑 JUnit —— 不需要 Android 环境。
    //
    // 能测的范围是 logcat 触发器的**纯函数部分**（解析 / 匹配 / 编解码）：
    // 它们是热路径，且失败模式是「触发器静默不触发」，必须有测试保护。
    // 流式 wrapper 本身与进程/线程相关，仍只能靠真机验证。
    testImplementation("junit:junit:4.13.2")

    add(r8Configuration.name, "com.android.tools:r8:${r8Version.get()}")
}

tasks.jar {
    dependsOn(generateCoreBuildInfo)
    manifest {
        attributes["Main-Class"] = "com.chaomixian.vflow.server.VFlowCore"
    }
    // 包含源码编译结果
    from(sourceSets.main.get().output)

    // 包含依赖库 (排除 android.jar 等 compileOnly 依赖)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })

    // 处理重复文件策略
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Dex 构建任务
tasks.register<BuildDexTask>("buildDex") {
    group = "build"
    description = "将 Jar 编译为 Dex 并复制到 App assets"

    dependsOn(tasks.jar)

    inputJar.set(tasks.jar.flatMap { it.archiveFile })
    androidJar.set(androidJarFile)
    javaExecutablePath.set(currentJavaExecutablePath)
    r8Classpath.from(r8Configuration)
    coreVersion.set(vflowCoreVersion)
    tempDexDir.set(layout.buildDirectory.dir("dex"))
    targetDex.set(rootProject.file("app/src/main/assets/vFlowCore.dex"))
    targetVersionFile.set(rootProject.file("app/src/main/assets/vFlowCore.version"))
}
