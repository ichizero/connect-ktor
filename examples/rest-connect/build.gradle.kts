plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinx.serialization)
    application
}

application {
    mainClass.set("io.github.ichizero.connect.ktor.example.MainKt")
}

kotlin {
    compilerOptions.allWarningsAsErrors.set(true)
}

dependencies {
    implementation(project(":library"))
    implementation(libs.bundles.connect)
    implementation(libs.bundles.ktor)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.cio)
    implementation(libs.bundles.protobuf)
    implementation(libs.kotlinx.serialization)

    testImplementation(libs.bundles.test)
    runtimeOnly(libs.slf4j.simple)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

sourceSets.main {
    java.srcDir("build/generated/sources/bufgen")
}

val generateProtos = tasks.register<Exec>("bufGenerate") {
    workingDir = projectDir
    commandLine("buf", "generate", "--template", "buf.gen.yaml")
    inputs.files("buf.gen.yaml", "buf.yaml")
    inputs.dir("proto")
    inputs.file(rootProject.file("protoc-gen-connect-ktor/out/protoc-gen-connect-ktor"))
    outputs.dir("build/generated/sources/bufgen")
}

tasks.named("compileKotlin") { dependsOn(generateProtos) }
tasks.named("compileJava") { dependsOn(generateProtos) }
