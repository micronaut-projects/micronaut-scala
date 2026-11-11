plugins {
    id("io.micronaut.build.internal.scala-compiler-variant")
}

// Scala 3.10 is not an LTS release, but the compiler plugin must still be compiled and
// published against its exact compiler API rather than using the Scala 3.9 artifact.
val supportedScalaVersion = libs.versions.scalaLatest.get()

description = "Micronaut Scala compiler plugin for Scala $supportedScalaVersion"

scalaCompilerVariant {
    scalaVersion.set(supportedScalaVersion)
}

micronautBuild {
    core {
        usesMicronautTest()
    }
    descriptor {
        parentModuleId = "io.micronaut.scala:micronaut-inject-scala_$supportedScalaVersion"
    }
    binaryCompatibility.enabledAfter("1.0.0")
}

// The primary variant owns the aggregated Javadoc for these shared sources.
configurations.named("internalJavadocElements") {
    outgoing.artifacts.clear()
}
configurations.named("internalJavadocClasspathElements") {
    outgoing.artifacts.clear()
}
