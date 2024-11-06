import com.vanniktech.maven.publish.KotlinJvm

plugins {
    application
    java
    kotlin("jvm")
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.shadow)
    id("com.vanniktech.maven.publish.base")
}

application {
    mainClass.set("io.github.ichizero.protocgen.connect.ktor.Main")
}

kotlin {
    compilerOptions.allWarningsAsErrors.set(true)
}

tasks {
    jar {
        manifest {
            attributes(mapOf("Main-Class" to application.mainClass.get()))
        }
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
            exclude("META-INF/**/*")
        }
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

dependencies {
    implementation(libs.bundles.ktor)
    implementation(libs.kotlinpoet)
    implementation(libs.protoc.gen.connect.kotlin)
    implementation(libs.protobuf.java)

    testImplementation(project(":ktor-serialization-connect"))
    testImplementation(libs.bundles.test)
    testImplementation(libs.bundles.connect)
    testImplementation(libs.bundles.protobuf)
    testImplementation(libs.kotlinx.serialization)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

sourceSets {
    test {
        java {
            srcDir(layout.buildDirectory.dir("generated/sources/bufgen"))
        }
    }
}

val shadowJarExecutable by tasks.registering(DefaultTask::class) {
    group = "Distribution"
    dependsOn(tasks.shadowJar)

    val shadowJarFile = tasks.shadowJar.orNull
        ?.outputs
        ?.files
        ?.singleFile
        ?: throw GradleException("ShadowJar task does not emit any output files")

    val outputDirectoryPath = layout.buildDirectory.dir("bin").get()
    val selfExecutableOutputPath = "$outputDirectoryPath/protoc-gen-connect-ktor"
    val windowsBatchFileOutputPath = "$outputDirectoryPath/protoc-gen-connect-ktor.bat"
    outputs.files(
        selfExecutableOutputPath,
        windowsBatchFileOutputPath,
    )

    doLast {
        File(selfExecutableOutputPath).apply {
            logger.lifecycle("Creating the self-executable file: $selfExecutableOutputPath")
            writeText(
                """
                #!/bin/sh
                exec java -Xmx512m -jar "$0" "$@"
                """.trimIndent(),
            )
            appendBytes(shadowJarFile.readBytes())
            setExecutable(true, false)
        }
        File(windowsBatchFileOutputPath).apply {
            writeText(
                """
                @echo off
                java -Xmx512m -jar "%~dp0%protoc-gen-connect-ktor" %*
                """.trimIndent(),
            )
        }
    }
    logger.lifecycle("Finished creating output files ktlint-cli")
}

mavenPublishing {
    configure(KotlinJvm())
}

extensions.getByType<PublishingExtension>().apply {
    publications
        .filterIsInstance<MavenPublication>()
        .forEach { publication ->
            publication.artifactId = "protoc-gen-connect-ktor"
        }
}
