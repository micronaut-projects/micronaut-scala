plugins {
    id("io.micronaut.build.internal.scala-compiler-variant")
}

val supportedScalaVersion = libs.versions.scala3.get()

description = "Micronaut Scala compiler plugin for Scala $supportedScalaVersion"

scalaCompilerVariant {
    scalaVersion.set(supportedScalaVersion)
}

micronautBuild {
    core {
        usesMicronautTest()
    }
    descriptor {
        // This is the root published module for this standalone repository.
        // Micronaut Build treats a self-parent as having no parent module.
        parentModuleId = "io.micronaut.scala:micronaut-inject-scala_$supportedScalaVersion"
    }
    binaryCompatibility.enabledAfter("1.0.0")
}
