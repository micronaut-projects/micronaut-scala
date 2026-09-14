plugins {
    id("base")
}

description = "Shared Micronaut Scala compiler-plugin test sources"

// The shared project is intentionally not published. The Scala compiler variant
// below compiles these sources and publishes the test support artifact.
