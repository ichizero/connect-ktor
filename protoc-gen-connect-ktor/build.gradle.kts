plugins {
    application
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
//    alias(libs.plugins.maven.publish)
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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// mavenPublishing {
//    configure(
//        KotlinJvm(),
//    )
// }
//
// extensions.getByType<PublishingExtension>().apply {
//    publications
//        .filterIsInstance<MavenPublication>()
//        .forEach { publication ->
//            publication.artifactId = "protoc-gen-connect-ktor"
//        }
// }
