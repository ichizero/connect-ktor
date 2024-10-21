plugins {
    application
    java
    alias(libs.plugins.kotlin.jvm)
//    alias(libs.plugins.maven.publish)
}

application {
    mainClass.set("io.ichizero.protocgen.connect.ktor.Main")
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
    }
}

dependencies {
    implementation(libs.protoc.gen.connect.kotlin)
    implementation(libs.protobuf.java)
    implementation(libs.kotlinpoet)
    implementation(libs.bundles.ktor)

    testImplementation(libs.bundles.test)
}

sourceSets {
    test {
        java {
            srcDir(layout.buildDirectory.dir("generated/sources/bufgen"))
        }
    }
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
