plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinx.serialization)
    application
}

application {
    mainClass.set("io.github.ichizero.connect.ktor.conformance.MainKt")
}

kotlin {
    compilerOptions {
        // Generated Kotlin code for protobuf uses OptIn annotation
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(project(":library"))
    implementation(libs.bundles.connect)
    implementation(libs.bundles.ktor)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.netty)
    implementation(libs.bundles.protobuf)
    implementation(libs.okio)

    runtimeOnly(libs.slf4j.simple)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

sourceSets {
    main {
        java {
            srcDir("build/generated/sources/bufgen")
        }
    }
}

tasks.named<JavaExec>("run") {
    // Conformance runner pipes stdin/stdout; keep them connected.
    standardInput = System.`in`
}

// Run `buf generate` automatically so that gradle-driven builds (CI, IDE) do
// not require the developer to run the Taskfile first. The protoc-gen-connect-ktor
// binary is expected at ../protoc-gen-connect-ktor/out/protoc-gen-connect-ktor;
// run `task plugin:build` (or the root `task build`) to produce it.
val generateProtos = tasks.register<Exec>("bufGenerate") {
    workingDir = projectDir
    commandLine("buf", "generate", "--template", "buf.gen.yaml")
    inputs.file("buf.gen.yaml")
    inputs.file("buf.yaml")
    outputs.dir("build/generated/sources/bufgen")
}

tasks.named("compileKotlin") {
    dependsOn(generateProtos)
}
tasks.named("compileJava") {
    dependsOn(generateProtos)
}
