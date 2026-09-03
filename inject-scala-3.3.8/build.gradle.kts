import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.scala.ScalaCompile

plugins {
    id("io.micronaut.build.internal.convention-library")
    id("scala")
}

description = "Micronaut Scala compiler plugin for Scala 3.3.8"

micronautBuild {
    core {
        usesMicronautTest()
    }
    binaryCompatibility.enabledAfter("1.0.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    named("main") {
        java.srcDir("../inject-scala/src/main/java")
        scala.srcDir("../inject-scala/src/main/scala")
        resources.srcDir("../inject-scala/src/main/resources")
    }
}

dependencies {
    api("io.micronaut:micronaut-core-processor:${libs.versions.micronaut.core.get()}") {
        exclude(group = "io.micronaut.sourcegen", module = "micronaut-sourcegen-bom")
        exclude(group = "org.scala-lang", module = "scala3-library_3")
    }
    api(libs.scala3.library)

    implementation(libs.asm)

    compileOnly(libs.scala3.compiler)

    testImplementation("io.micronaut:micronaut-context:${libs.versions.micronaut.core.get()}")
}

// Sourcegen and released Core versions may carry a newer Scala BOM. A compiler
// plugin must never be assembled against that version accidentally: this variant
// is deliberately pinned to its one supported compiler release.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.scala-lang" &&
            (requested.name == "scala3-library_3" || requested.name == "scala3-compiler_3")) {
            useVersion(libs.versions.scala3.get())
            because("compiler plugins are binary-compatible only with their exact Scala compiler release")
        }
    }
}

tasks.withType<ScalaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
    scalaCompileOptions.additionalParameters.add("-release:17")
    scalaCompileOptions.additionalParameters.add("-J--add-modules=java.compiler")
    scalaCompileOptions.forkOptions.jvmArgs = (scalaCompileOptions.forkOptions.jvmArgs ?: emptyList()) + "--add-modules=java.compiler"
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("micronaut-inject-scala_3.3.8")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
    // The Scala compiler plugin is loaded by a compiler-owned classloader. Keep
    // Micronaut and ASM implementation classes in the plugin, but let the host
    // compiler provide scala-library and scala3-library.
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile && it.extension == "jar" }
            .filterNot {
                it.name.startsWith("scala3-library_3-") ||
                    it.name.startsWith("scala-library-")
            }
            .map { zipTree(it) }
    })
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = "micronaut-inject-scala_3.3.8"
    }
}
