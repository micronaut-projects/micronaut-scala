import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("io.micronaut.build.internal.convention-library")
}

description = "Micronaut Scala compiler-plugin tests for Scala 3.3.8"

micronautBuild {
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
    compileOnly(project(":micronaut-inject-scala-3-3-8"))

    api("io.micronaut:micronaut-context:${libs.versions.micronaut.core.get()}")
    api(project(":micronaut-inject-scala-3-3-8"))
    api("io.micronaut:micronaut-retry:${libs.versions.micronaut.core.get()}")
    api(libs.managed.groovy)
    api(libs.scala3.compiler)
    api(libs.scala3.library)
    api(libs.spock) {
        exclude(module = "groovy-all")
    }

    testImplementation(libs.managed.graalvm.nativeimage)
    testImplementation("io.micronaut:micronaut-http:${libs.versions.micronaut.core.get()}")
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
    dependsOn(project(":micronaut-inject-scala-3-3-8").tasks.named("jar"))
    doFirst {
        systemProperty(
            "micronaut.scala.plugin.jar",
            project(":micronaut-inject-scala-3-3-8").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath
        )
        systemProperty("micronaut.scala.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("micronaut-inject-scala-test_3.3.8")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = "micronaut-inject-scala-test_3.3.8"
    }
}
