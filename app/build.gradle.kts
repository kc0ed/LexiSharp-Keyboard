plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
}

android {
    namespace = "com.brycewg.asrkb"
    compileSdk = 36

    defaultConfig {
        minSdk =29
        targetSdk = 34
        versionCode = 82
        versionName = "3.2.6" // 基础版本号

        // 仅打包 arm64-v8a 以控制体积；可按需扩展
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 定义风味维度
    flavorDimensions += "version"

    productFlavors {
        create("prod") {
            dimension = "version"
            applicationId = "com.brycewg.asrkb"
            // 确保 prod 版本没有这个标志或为 false
            buildConfigField("boolean", "IS_DEV_BUILD", "false")
        }
        create("dev") {
            dimension = "version"
            applicationId = "com.brycewg.asrkb.dev"
            versionNameSuffix = ".beta5-dev" // 更新 beta 版本
            // 为 dev 版本定义不同的应用名称和项目主页
            resValue("string", "app_name", "言犀键盘 Dev版")
            resValue("string", "about_project_url", "https://github.com/kc0ed/LexiSharp-Keyboard")
            // 添加一个构建时标志，用于在代码中区分 dev 版本
            buildConfigField("boolean", "IS_DEV_BUILD", "true")
        }
    }

    signingConfigs {
        create("release") {
            // 从环境变量读取签名配置
            storeFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 如果签名配置可用,则使用签名
            signingConfig = signingConfigs.findByName("release")?.takeIf {
                it.storeFile?.exists() == true
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // 由于应用在运行时支持手动切换语言，禁用 App Bundle 的按语言拆分，
    bundle {
        language {
            enableSplit = false
            // 自定义 APK 输出文件名，格式为：AppName-Version.apk
            applicationVariants.all {
                outputs.forEach { output ->
                    val outputImpl = output as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                    val appName = if (flavorName == "dev") "ASR-KB-Dev" else "ASR-Keyboard"
                    outputImpl.outputFileName = "${appName}-${versionName}.apk"
                }
            }
        }
    }
    // - 排除不需要的 JNI 库：
    //   * libonnxruntime4j_jni.so（ORT-Java 绑定，不使用，且常见 16KB 对齐问题）
    //   * libsherpa-onnx-c-api.so / libsherpa-onnx-cxx-api.so（C/C++ 接口，本项目走 JNI 不需要）
    packaging {
        jniLibs {
            excludes += listOf(
                "**/libonnxruntime4j_jni.so",
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so"
            )
        }
    }
}

// Kotlin 编译配置：使用 compilerOptions DSL 与 JDK 17 工具链
kotlin {
    // 使用本机 jbr-21 作为工具链，但 Kotlin 仍产出 JVM 17 目标字节码
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 让 Java 编译任务使用本机 JDK 21 工具链，同时保持源码/目标兼容为 17
val toolchainService = extensions.getByType(JavaToolchainService::class.java)
tasks.withType(JavaCompile::class.java).configureEach {
    javaCompiler.set(
        toolchainService.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.2.1")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // AAR 本地依赖占位：将 sherpa-onnx Kotlin API AAR 放入 app/libs/ 后自动识别
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}
