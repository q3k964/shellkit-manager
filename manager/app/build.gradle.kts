@file:Suppress("UnstableApiUsage")

import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.lsplugin.apksign)
    alias(libs.plugins.protobuf)
    id("kotlin-parcelize")
}

val androidCompileSdkVersion = rootProject.extra["androidCompileSdkVersion"] as Int
val androidCompileSdkVersionMinor = rootProject.extra["androidCompileSdkVersionMinor"] as Int
val androidBuildToolsVersion = rootProject.extra["androidBuildToolsVersion"] as String
val androidMinSdkVersion = rootProject.extra["androidMinSdkVersion"] as Int
val androidTargetSdkVersion = rootProject.extra["androidTargetSdkVersion"] as Int
val androidSourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
val androidTargetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion
val managerVersionCode = rootProject.extra["managerVersionCode"] as Int
val managerVersionName = rootProject.extra["managerVersionName"] as String

val isPrBuild = project.findProperty("IS_PR_BUILD")?.toString()?.toBoolean() ?: false
val defaultManagerPackageName = if (isPrBuild) "me.weishu.kernelsu.pr" else "me.weishu.kernelsu"
val defaultManagerName = if (isPrBuild) "KernelSU PR" else "KernelSU"
val managerPackageName = project.findProperty("KSU_PACKAGE_NAME")?.toString() ?: defaultManagerPackageName
val managerName = project.findProperty("KSU_NAME")?.toString() ?: defaultManagerName

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        ofNonTest().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

android {
    namespace = "me.weishu.kernelsu"

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        resValues = true
        compose = true
        prefab = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        generateLocaleConfig = true
    }
    compileSdk {
        version =
            release(androidCompileSdkVersion) {
                minorApiLevel = androidCompileSdkVersionMinor
            }
    }
    buildToolsVersion = androidBuildToolsVersion

    defaultConfig {
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = managerVersionCode
        versionName = managerVersionName
        applicationId = managerPackageName

        buildConfigField("boolean", "IS_PR_BUILD", isPrBuild.toString())
        resValue("string", "app_name", managerName)
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = androidSourceCompatibility
        targetCompatibility = androidTargetCompatibility
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes.addAll(listOf("META-INF/**", "kotlin/**", "**.bin"))
    }
}

base {
    archivesName.set(
        "${managerName.replace(" ", "_")}_${managerVersionName}_${managerVersionCode}"
    )
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigationevent.compose)

    implementation(libs.com.github.topjohnwu.libsu.core)
    implementation(libs.com.github.topjohnwu.libsu.service)
    implementation(libs.com.github.topjohnwu.libsu.io)

    implementation(libs.dev.rikka.rikkax.parcelablelist)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)

    implementation(libs.androidx.webkit)

    implementation(libs.lsposed.cxx)

    implementation(libs.hiddenapibypass)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    implementation(libs.material.kolor)

    implementation(libs.appiconloader)

    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.protobuf.kotlin.lite)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}
