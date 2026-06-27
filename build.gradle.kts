import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.spotless)
}
apply(plugin = "com.vanniktech.maven.publish.base")

group = "io.github.ichizero"

buildscript {
    dependencies {
        classpath(libs.maven.plugin)
        classpath(libs.kotlin.plugin)
        classpath(libs.spotless)
        classpath(libs.detekt.plugin)
    }
    repositories {
        mavenCentral()
    }
}

val snapshotVersion = "0.0.1"
val releaseVersion = project.findProperty("releaseVersion") as String? ?: snapshotVersion

allprojects {
    version = releaseVersion

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    apply(plugin = "com.diffplug.spotless")
    spotless {
        ratchetFrom("origin/main")
        isEnforceCheck = false
        kotlin {
            ktlint().editorConfigOverride(
                mapOf(
                    "ktlint_string-template-indent" to "disabled",
                ),
            )
            target("**/*.kt")
            targetExclude("**/connect/ktor/internal/*.kt")
        }
        kotlinGradle {
            ktlint().editorConfigOverride(
                mapOf(
                    "ktlint_string-template-indent" to "disabled",
                ),
            )
            target("**/*.kts")
        }
    }

    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        source.setFrom(
            files(
                "src/main/kotlin",
                "src/test/kotlin",
            ),
        )
    }
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "21"
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(false)
            md.required.set(false)
        }
    }
    tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
        jvmTarget = "21"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    plugins.withId("com.vanniktech.maven.publish.base") {
        configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()

            coordinates("io.github.ichizero", name, version.toString())

            pom {
                name.set("connect-ktor") // Rewrite on each project.
                description.set("Use Connect with Ktor Server.")
                inceptionYear.set("2024")
                url.set("https://github.com/ichizero/connect-ktor")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("ichizero")
                        name.set("ichizero")
                        url.set("https://github.com/ichizero")
                    }
                }
                scm {
                    url.set("https://github.com/ichizero/connect-ktor")
                    connection.set("scm:git:git://github.com/ichizero/connect-ktor.git")
                    developerConnection.set("scm:git:ssh://git@github.com/ichizero/connect-ktor.git")
                }
            }
        }
    }
}
