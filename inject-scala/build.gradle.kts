plugins {
    id("base")
}

description = "Shared Micronaut Scala compiler-plugin sources"

// The shared project is intentionally not published. Compiler-specific projects
// below compile these source trees and own the published, fully-crossed artifact.
