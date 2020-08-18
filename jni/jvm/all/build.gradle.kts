plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":jni:jvm:darwin"))
    api(project(":jni:jvm:linux"))
    api(project(":jni:jvm:mingw"))
}

publishing {
    publications {
        create<MavenPublication>("jvm") {
            artifactId = "secp256k1-jni-jvm"
            from(components["java"])
        }
    }
}