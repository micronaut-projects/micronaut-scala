# Scala 3 support maintenance and release plan

This document belongs to `micronaut-scala`, which owns the Scala compiler
adapter, compiler-versioned publications, and Scala parity tests. Micronaut
Core owns only the language-neutral SPI consumed by this repository.

## Compatibility policy

Scala compiler APIs are not a binary-compatible API surface. Every compiler
plugin publication therefore targets one exact Scala compiler release and uses
the full compiler suffix. The initial line is:

* Scala compiler: `3.9.0`
* plugin: `io.micronaut.scala:micronaut-inject-scala_3.9.0`
* test support: `io.micronaut.scala:micronaut-inject-scala-test_3.9.0`

When a later Scala release is supported, add a parallel source-compiling
variant and publication. Do not replace or republish the existing artifact
under a different compiler ABI. The Scala compiler and this repository’s
integration version remain separate version axes.

## Bootstrap and release sequence

1. Build against the Core SPI branch with IncludeGit and a local Core checkout
   when developing locally.
2. Run the complete `micronaut-inject-scala-test-compiler` suite and
   `verifyCompilerArtifacts`.
3. After Core publishes a release containing the SPI, replace branch
   substitution with that released Core baseline.
4. Publish the snapshot for integration testing, then publish `1.0.0` only
   after the released Core dependency is used by the build.

The branch include is a development facility and is opt-in by default. A local
Core checkout also opts in automatically for development. Keep the released
Core version in the version catalog as the normal dependency path.

## Packaging rules

The Scala compiler supplies Scala compiler and standard-library classes to the
plugin classloader. The assembled plugin may include Micronaut implementation
and ASM classes needed by its isolated adapter, but must not embed
`scala3-compiler`, `scala3-library`, `scala-library`, or `dotty` classes. The
publication POM and BOM must use the same full-cross artifact IDs as the JAR
files.

Scala 2 and GraalPy are out of scope for this repository.
