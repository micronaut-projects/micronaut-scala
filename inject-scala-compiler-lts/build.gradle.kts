plugins {
    id("io.micronaut.build.internal.scala-compiler-variant")
}

// The previous Scala 3 LTS line. A second variant exists to prove the arrangement works:
// the plugin sources are compiled unchanged against a different compiler release, and the
// two publications differ only in the compiler version in their coordinates.
val supportedScalaVersion = libs.versions.scalaLts.get()

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

// The variants compile the same sources, so aggregating javadoc from both offers the reader
// nothing and fails outright: the shared `package-info.java` is contributed twice and javadoc
// rejects the duplicate package annotation. The primary variant documents the code, and this
// one withdraws from the aggregation rather than duplicating it.
configurations.named("internalJavadocElements") {
    outgoing.artifacts.clear()
}
configurations.named("internalJavadocClasspathElements") {
    outgoing.artifacts.clear()
}
