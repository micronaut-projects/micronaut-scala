plugins {
    id("io.micronaut.build.internal.bom")
}

group = properties["projectGroupId"].toString()
version = properties["projectVersion"].toString()

micronautBuild {
    // A BOM has no binary API baseline. The component publications enable
    // compatibility checks once the independent 1.0.0 line is released.
    binaryCompatibility.enabled = false
}

micronautBom {
    propertyName = "scala"
    extraExcludedProjects = listOf(
        "inject-scala",
        "inject-scala-3-3-8",
        "inject-scala-test",
        "inject-scala-test-3-3-8",
        "micronaut-inject-scala",
        "micronaut-inject-scala-3-3-8",
        "micronaut-inject-scala-test",
        "micronaut-inject-scala-test-3-3-8"
    )
    suppressions {
        dependencies.add("com.fasterxml.jackson.core:jackson-annotations:2.22")
    }
}

// The build plugin derives managed artifact IDs from Gradle project names. The
// compiler version is intentionally encoded with dots in the Maven coordinates,
// so declare the two fully-crossed publications explicitly in the platform.
dependencies {
    constraints {
        add("api", "io.micronaut.scala:micronaut-inject-scala_3.3.8:${project.version}")
        add("api", "io.micronaut.scala:micronaut-inject-scala-test_3.3.8:${project.version}")
    }
}
