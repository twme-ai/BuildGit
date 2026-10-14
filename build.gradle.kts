plugins { base }
// 發布時由 CI 以 -PbuildgitVersion=<標籤> 指定版本
val buildgitVersion = (findProperty("buildgitVersion") as String?) ?: "0.1.0-SNAPSHOT"
allprojects { group = "dev.twme.buildgit"; version = buildgitVersion }
subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach { options.release.set(21); options.encoding = "UTF-8" }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1g"
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
    }
}
tasks.named("build") { dependsOn(subprojects.map { it.tasks.named("build") }) }
