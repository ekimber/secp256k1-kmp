import org.apache.http.impl.client.HttpClients
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.auth.UsernamePasswordCredentials
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "1.4.0"
    `maven-publish`
}

buildscript {
    repositories {
        google()
        jcenter()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:4.0.1")
    }
}

allprojects {
    group = "fr.acinq.secp256k1"
    version = "0.4.0"

    repositories {
        jcenter()
        google()
    }
}

val currentOs = OperatingSystem.current()

kotlin {
    explicitApi()

    val commonMain by sourceSets.getting

    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }

    fun KotlinNativeTarget.secp256k1CInterop(target: String) {
        compilations["main"].cinterops {
            val libsecp256k1 by creating {
                includeDirs.headerFilterOnly(project.file("native/secp256k1/include/"))
                tasks[interopProcessingTaskName].dependsOn(":native:buildSecp256k1${target.capitalize()}")
            }
        }
    }

    val nativeMain by sourceSets.creating { dependsOn(commonMain) }

    linuxX64("linux") {
        secp256k1CInterop("host")
        compilations["main"].defaultSourceSet.dependsOn(nativeMain)
        // https://youtrack.jetbrains.com/issue/KT-39396
        compilations["main"].kotlinOptions.freeCompilerArgs += listOf("-include-binary", "$rootDir/native/build/linux/libsecp256k1.a")
    }

    ios {
        secp256k1CInterop("ios")
        // https://youtrack.jetbrains.com/issue/KT-39396
        compilations["main"].defaultSourceSet.dependsOn(nativeMain)
        // https://youtrack.jetbrains.com/issue/KT-39396
        compilations["main"].kotlinOptions.freeCompilerArgs += listOf("-include-binary", "$rootDir/native/build/ios/libsecp256k1.a")
    }

    sourceSets.all {
        languageSettings.useExperimentalAnnotation("kotlin.RequiresOptIn")
    }

}

// Disable cross compilation
allprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        afterEvaluate {
            val currentOs = OperatingSystem.current()
            val targets = when {
                currentOs.isLinux -> listOf()
                currentOs.isMacOsX -> listOf("linux")
                currentOs.isWindows -> listOf("linux")
                else -> listOf("linux")
            }.mapNotNull { kotlin.targets.findByName(it) as? KotlinNativeTarget }

            configure(targets) {
                compilations.all {
                    cinterops.all { tasks[interopProcessingTaskName].enabled = false }
                    compileKotlinTask.enabled = false
                    tasks[processResourcesTaskName].enabled = false
                }
                binaries.all { linkTask.enabled = false }

                mavenPublication {
                    val publicationToDisable = this
                    tasks.withType<AbstractPublishToMaven>().all { onlyIf { publication != publicationToDisable } }
                    tasks.withType<GenerateModuleMetadata>().all { onlyIf { publication.get() != publicationToDisable } }
                }
            }
        }
    }
}

val snapshotNumber: String? by project
val gitRef: String? by project
val eapBranch = gitRef?.split("/")?.last() ?: "dev"
val bintrayVersion = if (snapshotNumber != null) "${project.version}-$eapBranch-$snapshotNumber" else project.version.toString()
val bintrayRepo = if (snapshotNumber != null) "snapshots" else "libs"

val bintrayUsername: String? = (properties["bintrayUsername"] as String?) ?: System.getenv("BINTRAY_USER")
val bintrayApiKey: String? = (properties["bintrayApiKey"] as String?) ?: System.getenv("BINTRAY_APIKEY")
val hasBintray = bintrayUsername != null && bintrayApiKey != null
if (!hasBintray) logger.warn("Skipping bintray configuration as bintrayUsername or bintrayApiKey is not defined")

allprojects {
    plugins.withId("maven-publish") {
        publishing {
            if (hasBintray) {
                repositories {
                    maven {
                        name = "bintray"
                        setUrl("https://api.bintray.com/maven/acinq/$bintrayRepo/${rootProject.name}/;publish=0")
                        credentials {
                            username = bintrayUsername
                            password = bintrayApiKey
                        }
                    }
                }
            }

            publications.withType<MavenPublication>().configureEach {
                version = bintrayVersion
                pom {
                    description.set("Bitcoin's secp256k1 library ported to Kotlin/Multiplatform for JVM, Android, iOS & Linux")
                    url.set("https://github.com/ACINQ/secp256k1-kmp")
                    licenses {
                        name.set("Apache License v2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                    issueManagement {
                        system.set("Github")
                        url.set("https://github.com/ACINQ/secp256k1-kmp/issues")
                    }
                    scm {
                        connection.set("https://github.com/ACINQ/secp256k1-kmp.git")
                    }
                }
            }
        }
    }
}

if (hasBintray) {
    val postBintrayPublish by tasks.creating {
        doLast {
            HttpClients.createDefault().use { client ->
                val post = HttpPost("https://api.bintray.com/content/acinq/$bintrayRepo/${rootProject.name}/$bintrayVersion/publish").apply {
                    entity = StringEntity("{}", ContentType.APPLICATION_JSON)
                    addHeader(BasicScheme().authenticate(UsernamePasswordCredentials(bintrayUsername, bintrayApiKey), this, null))
                }
                client.execute(post)
            }
        }
    }

    val postBintrayDiscard by tasks.creating {
        doLast {
            HttpClients.createDefault().use { client ->
                val post = HttpPost("https://api.bintray.com/content/acinq/$bintrayRepo/${rootProject.name}/$bintrayVersion/publish").apply {
                    entity = StringEntity("{ \"discard\": true }", ContentType.APPLICATION_JSON)
                    addHeader(BasicScheme().authenticate(UsernamePasswordCredentials(bintrayUsername, bintrayApiKey), this, null))
                }
                client.execute(post)
            }
        }
    }
}

allprojects {
    afterEvaluate {
        tasks.withType<AbstractTestTask>() {
            testLogging {
                events("passed", "skipped", "failed", "standard_out", "standard_error")
                showExceptions = true
                showStackTraces = true
            }
        }
    }
}
