import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("io.micronaut.build.internal.convention-library")
}

val scalaVersion = libs.versions.scala3.get()
val pluginArtifactId = "micronaut-inject-scala_$scalaVersion"
val testArtifactId = "micronaut-inject-scala-test_$scalaVersion"
val scalaPluginProject = project(":micronaut-inject-scala-compiler")

description = "Micronaut Scala compiler-plugin tests for Scala $scalaVersion"

micronautBuild {
    descriptor {
        parentModuleId = "io.micronaut.scala:$pluginArtifactId"
    }
    binaryCompatibility.enabledAfter("1.0.0")
}

sourceSets {
    named("main") {
        java.srcDir("../inject-scala-test/src/main/java")
        groovy.srcDir("../inject-scala-test/src/main/groovy")
        resources.srcDir("../inject-scala-test/src/main/resources")
    }
    named("test") {
        java.srcDir("../inject-scala-test/src/test/java")
        groovy.srcDir("../inject-scala-test/src/test/groovy")
        resources.srcDir("../inject-scala-test/src/test/resources")
    }
}

dependencies {
    compileOnly(scalaPluginProject)

    api(libs.micronaut.context)
    api(scalaPluginProject)
    api(libs.micronaut.retry)
    api(libs.managed.groovy)
    api(libs.scala3.compiler)
    api(libs.scala3.library)
    api(libs.spock) {
        exclude(module = "groovy-all")
    }

    testImplementation(libs.managed.graalvm.nativeimage)
    testImplementation(libs.micronaut.http)
    testImplementation(platform(libs.test.boms.micronaut.validation))
    testImplementation(libs.micronaut.validation) {
        exclude(group = "io.micronaut")
    }
    testImplementation(libs.micronaut.validation.processor) {
        exclude(group = "io.micronaut")
    }
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.scala-lang" &&
            (requested.name == "scala3-library_3" || requested.name == "scala3-compiler_3")) {
            useVersion(libs.versions.scala3.get())
            because("the test harness must exercise the exact compiler version published by this variant")
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(scalaPluginProject.tasks.named("jar"))
    doFirst {
        systemProperty(
            "micronaut.scala.plugin.jar",
            scalaPluginProject.tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath
        )
        systemProperty("micronaut.scala.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(testArtifactId)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = testArtifactId
    }
}
