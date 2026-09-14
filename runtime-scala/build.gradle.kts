plugins {
    id("io.micronaut.build.internal.convention-library")
}

description = "Micronaut Scala runtime support"

micronautBuild {
    descriptor {
        // The compiler plugin is the root published module for this repository; this
        // artifact hangs off it rather than being a parent of anything.
        parentModuleId = "io.micronaut.scala:micronaut-inject-scala_${libs.versions.scala3.get()}"
    }
    binaryCompatibility.enabledAfter("1.0.0")
}

// The application-side half of Scala support, published on its own so that a user does not
// have to put the compiler plugin on a running application's classpath to get Scala
// collection conversion. The plugin jar bundles Micronaut and ASM at original package paths,
// so `runtimeOnly` on it dragged both into the running application.
//
// A distinct package from the plugin, deliberately: `io.micronaut.scala.processing` exists in
// the plugin jar, and the same package in two jars is a split package.
dependencies {
    api(libs.micronaut.core)
    api(libs.scala3.library)

    // Serialization support is compiled against but not required at runtime: the beans declare
    // `@Requires(classes = ...)`, so an application that does not serialize never loads them and
    // never needs the dependency.
    compileOnly(platform(libs.test.boms.micronaut.serde))
    compileOnly(libs.micronaut.serde.api)
    annotationProcessor(libs.micronaut.inject.java)
}
