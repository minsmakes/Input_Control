import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ndkRelease = "27.3.13750724"
val nativeDir = layout.buildDirectory.dir("generated/inputNative")
val releaseMetadata = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("../release/version.properties")).asText.get().reader())
}

configurations.configureEach {
    if (name == "releaseCompileClasspath" || name == "releaseRuntimeClasspath") {
        resolutionStrategy.activateDependencyLocking()
    }
}
dependencyLocking { lockMode = LockMode.STRICT }

android {
    namespace = "io.inputcontrol"
    compileSdk { version = release(37) }
    buildToolsVersion = "36.0.0"
    ndkVersion = ndkRelease
    defaultConfig {
        applicationId = "io.inputcontrol"
        minSdk = 28
        targetSdk = 36
        versionCode = releaseMetadata.getProperty("versionCode").toInt()
        versionName = releaseMetadata.getProperty("versionName")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets["main"].jniLibs.directories.add(nativeDir.get().asFile.path)
}

val sdkDir = androidComponents.sdkComponents.sdkDirectory
val crateDir = rootDir.resolve("../rust-core").canonicalFile
val os = System.getProperty("os.name")
val windows = os.startsWith("Windows")
val host =
    when {
        windows -> "windows-x86_64"
        os.startsWith("Mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
val cargoBin =
    file(System.getProperty("user.home")).resolve(".cargo/bin/cargo" + if (windows) ".exe" else "")

for ((abi, target) in
    mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")) {
    val buildRust =
        tasks.register<Exec>("compileRust" + abi.replace("-", "").replace("_", "")) {
            workingDir(crateDir)
            inputs.files(fileTree(crateDir) { include("src/**", "Cargo.toml", "Cargo.lock") })
            inputs.files(fileTree(crateDir.resolve("../wifi-protocol")) { include("src/**","Cargo.toml") })
            inputs.property("ndk", ndkRelease)
            inputs.file(crateDir.resolve("../rust-toolchain.toml"))
            val output = nativeDir.get().file("$abi/libinput_control.so").asFile
            outputs.file(output)
            doFirst {
                val bin =
                    sdkDir
                        .get()
                        .asFile
                        .resolve("ndk/$ndkRelease/toolchains/llvm/prebuilt/$host/bin")
                val clang = bin.resolve("clang" + if (windows) ".exe" else "")
                check(clang.exists()) { "Install Android NDK $ndkRelease in SDK Manager" }
                environment(
                    "CARGO_TARGET_" + target.uppercase().replace('-', '_') + "_LINKER",
                    clang.path,
                )
                environment(
                    "CARGO_TARGET_" + target.uppercase().replace('-', '_') + "_RUSTFLAGS",
                    "-C link-arg=--target=${target}28 -C link-arg=-Wl,-z,max-page-size=16384",
                )
            }
            commandLine(
                if (cargoBin.exists()) cargoBin.path else "cargo",
                "build",
                "--locked",
                "--release",
                "--target",
                target,
            )
            doLast {
                output.parentFile.mkdirs()
                crateDir
                    .resolve("target/$target/release/libinput_control.so")
                    .copyTo(output, overwrite = true)
            }
        }
    tasks.named("preBuild") { dependsOn(buildRust) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
