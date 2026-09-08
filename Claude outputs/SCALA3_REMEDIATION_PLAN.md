# Scala 3 support: gap analysis and remediation plan

Companion to `SCALA3_SUPPORT_PLAN.md` (which covers compatibility policy, release
sequencing and packaging rules) and to `inject-scala-test/DISABLED_TESTS.md`
(which tracks parity-test coverage). This document records what a full review of
the implementation found, and the order in which it should be fixed.

Reviewed at commit `04c3d4f` on `codex/scala-3.3.8-support`.

## How this was produced

Four independent reviews of the checkout:

1. the dotty plugin driver and phase model (`MicronautScalaCompilerPlugin.scala`,
   `ScalaProcessingEngine`);
2. the Micronaut Element API adapter (`ScalaClassElement`, `ScalaLoadedClassElement`
   and the member/type elements);
3. the annotation-metadata pipeline (`ScalaAnnotationMetadataBuilder`,
   `ClasspathAnnotationMetadataReader`, `ScalaCollectionConverterRegistrar`);
4. build, packaging, publication, CI and documentation.

Findings marked **confirmed** were verified against primary sources
(`scala3-compiler_3:3.9.0` sources, the checked-in code). Findings marked
**suspected** are strongly evidenced by reading but need a test to settle.

---

## Executive summary

The implementation is substantial and in good shape structurally: the extraction
phase reduces dotty trees to plain records, the Element API and annotation
builder are then ordinary Java against Micronaut's SPI, and the 220-feature
Spock suite covers a genuinely wide surface. The problems are concentrated in
five areas.

1. **Flag testing is wrong.** Four uses of composite dotty `FlagSet`s go through
   `isOneOf` (intersection) where they need `isAllOf` (containment). Confirmed
   against the 3.9.0 compiler sources. Every Java class on the classpath is
   currently classified as an interface.
2. **The pipeline is driven by a unit-count heuristic** that can silently skip
   every `TypeElementVisitor` while still generating bean definitions, and that
   does not survive macro-suspended units or a second `Run`.
3. **Errors carry no source position.** The whole diagnostic channel is
   `Consumer<String>`, so `ProcessingException`'s originating element is
   discarded before it reaches `report.error`.
4. **Classpath types are a second, weaker pipeline.** `ScalaLoadedClassElement`
   is reflective, its annotation metadata bypasses
   `AbstractAnnotationMetadataBuilder` entirely (so no stereotypes, no aliases,
   no mappers, no defaults), and it disagrees with the source elements on
   modifiers, equality, generics and enum-ness.
5. **Packaging and publication are not releasable.** The fat jar is unrelocated
   while the POM still declares the same dependencies, `micronaut-core` is
   pinned to an unpublished snapshot, and the one packaging guard
   (`verifyCompilerArtifacts`) never runs and contains two assertions that can
   never fire.

Documentation is effectively absent for users: 51 lines of quick start, no
working build file for any tool, and the sbt/Mill advice as written produces a
double version suffix.

---

## A. Confirmed correctness defects

### A1 (BLOCKER, confirmed) Composite `FlagSet`s tested with `isOneOf`

`inject-scala/src/main/scala/io/micronaut/scala/processing/MicronautScalaCompilerPlugin.scala:1645`

```scala
private def hasFlag(symbol: Symbol, flag: Flags.FlagSet)(using Context): Boolean =
  symbol != Symbols.NoSymbol && symbol.denot.isOneOf(flag)
```

`isOneOf` is defined in `dotty/tools/dotc/core/Flags.scala:73` as a non-empty
intersection. Four call sites pass *conjunction* sets, which dotty itself always
tests with `isAllOf`:

| Site | Set | Definition in 3.9.0 | Effect of `isOneOf` |
| --- | --- | --- | --- |
| `:1614` | `Flags.JavaInterface` | `JavaDefined \| NoInits \| Trait` | true for **every** `JavaDefined` symbol, so `isInterfaceSymbol` reduces to `hasFlag(symbol, Flags.JavaDefined)` |
| `:1595` | `Flags.JavaEnumValue` | `JavaDefined \| EnumValue` | true for every `JavaDefined` symbol |
| `:1594` | `Flags.EnumValue` | `Enum \| StableRealizable` | true for every stable val |
| `:1596` | `Flags.EnumCase` | `Case \| Enum` | true for every case-class member |

Downstream consequences: in `toClassData` (`:468`) and `typeHierarchy` (`:921`)
a Java superclass is recorded as an interface and `superType` becomes `null`, so
the annotation hierarchy walk and `ClassElement.getSuperType()` are wrong for
every Scala class extending a Java class; `isEnumConstant` (`:1592`) misfires in
`annotationValue` (`:1360`), turning ordinary `Select` trees into enum-constant
names.

**Fix.** Add a second helper and use it for conjunction sets:

```scala
private def hasAllFlags(symbol: Symbol, flags: Flags.FlagSet)(using Context): Boolean =
  symbol != Symbols.NoSymbol && symbol.denot.isAllOf(flags)
```

Keep `isOneOf` only for the deliberate union at `:1633`
(`Flags.Synthetic | Flags.Artifact | Flags.Accessor`). Add a regression test that
asserts a Scala class extending a classpath Java class reports that class as its
super type, not as an interface.

### A2 (BLOCKER, confirmed) Pipeline gated on a compilation-unit count

`MicronautScalaCompilerPlugin.scala:188`, `:205`

```scala
if !typeVisitorsProcessed && typeUnitsSeen >= unitCount then
```
```scala
private def unitCount(using ctx: Context): Int =
  math.max(1, ctx.run.units.size)
```

`Phase.runOn` skips units for which `ctx.run.enterUnit(unit)` is false, so
`typeUnitsSeen` can never reach `ctx.run.units.size`. When that happens
`processTypeVisitors()` never runs, but `BeanDefinitionPhase.run` (`:278`) still
calls `processBeanDefinitions()`. The result is bean definitions generated with
no visitor having run: no AOP, no introspections, no validation, no user
visitors, and no diagnostic.

**Fix.** Drive the pipeline from a point that is guaranteed to run once after all
units — override `runOn(units)` rather than `run` — and make
`processBeanDefinitions()` unconditionally ensure `processTypeVisitors()` has
run first, so the ordering invariant cannot be violated by any future change.

### A3 (BLOCKER, confirmed) State is not keyed per `Run`

`MicronautScalaCompilerPlugin.scala:180`

```scala
private var typeUnitsSeen = 0
private var typeVisitorsProcessed = false
private var beanDefinitionsProcessed = false
```

Plugin phase instances are memoised in `ContextBase`, and `Driver.finish`
re-runs macro-suspended units in a new `Run` against the same compiler. Both
booleans are already `true` on that second run (as are the equivalents at
`ScalaProcessingEngine.java:76`), so suspended units are collected and then
silently discarded. Inline/macro expansion suspending a unit is ordinary in
Scala 3; the test harness compiles one file per test and so never reaches this.

**Fix.** Key `ProcessingState` on `ctx.run` identity (or attach it to the `Run`),
and reset the engine when a new run is observed.

### A4 (BLOCKER, confirmed) `withTypeArguments` discards generics

`inject-scala/src/main/java/io/micronaut/scala/processing/visitor/ScalaClassElement.java:719`

```java
public ClassElement withTypeArguments(Map<String, ClassElement> typeArguments) {
    return this;
```

Micronaut calls this wherever it resolves generics (`foldBoundGenericTypes`,
factory return-type resolution, `AstBeanPropertiesUtils`). Returning `this` hands
the caller the unsubstituted type with no way to detect it. The classpath side
implements it correctly (`ScalaLoadedClassElement.java:300`), so source and
classpath elements disagree.

**Fix.** Add `ScalaTypeData.withTypeArguments(...)` alongside the existing
`withArrayDimensions`, and build a new `ScalaClassElement` that preserves
`classData` so members survive the copy.

### A5 (BLOCKER, confirmed) `MethodElement.overrides` and `hides` always return false

`ScalaMethodElement.java:218`, `:223`

```java
public boolean overrides(MethodElement overridden) {
    return false;
}
```

`BeanDefinitionCreatorFactory` uses `overrides` to merge annotation metadata from
the overridden parent method, to avoid emitting a second injection point, and for
interception decisions. An `@Inject`/`@PostConstruct`/`@Executable` method
overridden in a subclass is currently treated as two unrelated methods.

**Fix.** Implement as name plus erased parameter-signature match, guarded on
`!isPrivate() && !isStatic()` and on the declaring types being related. The
`MethodSignature`/`TypeSignature` records at `ScalaClassElement.java:744` already
provide the comparison key.

### A6 (BLOCKER, confirmed) Inherited members from classpath supertypes are invisible

`ScalaClassElement.java:455`

```java
Optional<ScalaClassElement> sourceElement = visitorContext.sourceClassElement(type.name());
if (sourceElement.isEmpty()) {
    return;
}
```

`collectInheritedMethods` gives up unless the supertype is in the current
compilation. A Scala bean extending an abstract class or trait from another
module reports none of its inherited methods: no inherited `@Inject` setters, no
inherited `@Executable`/`@Around` methods, no inherited abstract methods for
introspection. `ALL_FIELDS` is worse — `addFieldElements` (`:596`) never walks
the hierarchy at all, so inherited `@Inject`/`@Value` fields and inherited
`@ConfigurationProperties` state are always missed.

**Fix.** Fall back to `visitorContext.getClassElement(type.name())` and merge its
enclosed elements under the same signature de-duplication; mirror the method walk
for fields, honouring `isOnlyDeclared()` and name shadowing.

### A7 (BLOCKER, confirmed) Classpath annotation metadata bypasses the metadata builder

`ClasspathAnnotationMetadataReader.java:156`

```java
return new AnnotationValueVisitor(descriptor, annotationValue ->
    annotationMetadata.addDeclaredAnnotation(
        annotationValue.getAnnotationName(),
        annotationValue.getValues(),
        visible ? RetentionPolicy.RUNTIME : RetentionPolicy.CLASS
    )
);
```

Raw ASM output goes straight into a `MutableAnnotationMetadata`. Nothing walks
meta-annotations, so on a classpath type `hasStereotype(AnnotationUtil.SCOPE)`
and `hasStereotype(AnnotationUtil.QUALIFIER)` are false, `@AliasFor` never
resolves, the `AnnotationMapper`/`AnnotationTransformer`/`AnnotationRemapper` SPI
never runs, repeatable containers are not unwrapped, and defaults are absent.
The same type inspected from source and from the classpath gives different
answers.

**Fix.** Adapt the ASM result into `ScalaAnnotationData`/`ScalaAnnotationTypeData`
(recursively reading each annotation type's own class file) and feed it through
`ScalaAnnotationMetadataBuilder`, so both element kinds share one metadata
pipeline.

### A8 (BLOCKER, confirmed) No source positions on any diagnostic

`MicronautScalaCompilerPlugin.scala:215`

```scala
message => report.inform(message),
message => report.warning(message),
message => report.error(message)
```

The reporter channel is `Consumer<String>`. `VisitorContext.fail(message, element)`
reports and throws, but the element never reaches `report.error`, which then uses
`NoSourcePosition`. Every Micronaut error — `@Inject` on a final field, invalid
`@ConfigurationProperties`, any visitor `fail(...)` — is emitted with no file,
line or caret. `reportProcessingException` (`:232`) drops the element too. The
information exists: every data record carries a `nativeType` that is a `tpd.Tree`
or `Symbol` with a usable `srcPos`.

**Fix.** Widen the reporter to `BiConsumer<String, Object>`, resolve `srcPos`
from the native type in the plugin, and pass it to `report.error`/`report.warning`.
Then extend the harness (see C1) so tests can assert on positions.

### A9 (MAJOR, confirmed) Non-constant annotation values become printed tree text

`MicronautScalaCompilerPlugin.scala:1366`

```scala
case _ =>
  renderedClassLiteralValue(tree.show).map(name => classValueData(name)).getOrElse(tree.show)
```

Any annotation argument the extractor does not understand is stored as the
compiler's *display string*. In Scala 3 a plain `val BAR = "x"` in an `object`
has no `ConstantType`, so `@Value(Foo.BAR)` silently stores the literal string
`"Foo.BAR"` and bakes it into the bean definition. `tree.show` can also embed
ANSI escapes — `renderedClassLiteralValue` (`:1552`) strips them, which is an
admission that this depends on printer formatting rather than an API contract.

The mirror-image defect is at `:1405` and `:1432`: any `String` constant whose
*content* contains `classOf` is converted into an `AnnotationClassValue`, so
`@Value("classOf[Int] is the tag")` becomes a class reference.

**Fix.** Handle the remaining tree shapes structurally (`Ident`/`Select` to a
`ConstantType`, `Inlined`, `Block`, array construction), and report an error at
the argument position for anything left over. Delete every
`renderedClassLiteralValue` fallback; detect class literals from the tree
(`isClassOf`, already present at `:1479`), never from string content.

### A10 (MAJOR, confirmed) Annotation defaults exist only for the current compilation unit

`MicronautScalaCompilerPlugin.scala:349`

```scala
given AnnotationDefaults = AnnotationDefaults(annotationDefaultValues(unit.tpdTree))
```

Defaults are harvested by scanning the current unit's trees for
`$lessinit$greater$default$N`. So an annotation declared in another file of the
same compilation has no defaults, and every classpath annotation
(`@Requires`, `@Property`, `@Executable`, `@Bean`, `@ConfigurationProperties`)
has none at all. `ClasspathAnnotationMetadataReader` compounds this: its
`MethodVisitor` (`:247`) never overrides `visitAnnotationDefault`.

**Fix.** Resolve defaults from the annotation *symbol* on demand and cache per
annotation type, which also covers classfile-loaded Java annotations; add
`visitAnnotationDefault` to the ASM reader.

### A11 (MAJOR, confirmed) `getAnnotationMirror` makes metadata order-dependent

`ScalaAnnotationMetadataBuilder.java:270`

```java
protected Optional<Object> getAnnotationMirror(String annotationName) {
    return Optional.ofNullable(nativeAnnotationTypes.get(annotationName))
        .map(nativeType -> new AnnotationTypeElement(annotationName, nativeType));
}
```

`nativeAnnotationTypes` is populated lazily as elements are visited. The
superclass uses this mirror to resolve stereotypes for annotations added
programmatically (`element.annotate(...)`, mappers, `AbstractBeanDefinitionBuilder`).
If the type has not been seen yet, no meta-annotations are processed — so
`annotate(Executable.class)` can produce a bare `@Executable` with no stereotypes,
and whether it does depends on compilation-unit order. `getRepeatableContainerNameForType`
(`:265`) and `getRetentionPolicy` (`:306`) have the same shape.

**Fix.** Fall back to resolving the annotation type by name through the dotty
context (`Symbols.getClassIfDefined`, already used at `:1616`) and build a
`ScalaAnnotationTypeData` on demand.

### A12 (MAJOR, confirmed) Retention defaults to RUNTIME, and `valueOf` is unguarded

`ScalaAnnotationMetadataBuilder.java:305`

```java
if (nativeType != null && nativeType.retentionPolicyName() != null) {
    return RetentionPolicy.valueOf(nativeType.retentionPolicyName());
}
return RetentionPolicy.RUNTIME;
```

The JLS default is `CLASS`. A `StaticAnnotation` subclass or a Java annotation
with no `@Retention` is treated as `RUNTIME` and written into the bean
definition. `RetentionPolicy.valueOf` is also unguarded on a value that, by A9,
can be rendered tree text, and will throw `IllegalArgumentException` out of the
plugin. `@Target` is captured nowhere at all — which matters more in Scala than
in Java, because Scala copies an annotation on a `val` onto the field, the getter
and the constructor parameter unless meta-annotations restrict it.

**Fix.** Default to `CLASS`, guard the `valueOf`, capture `targetNames` on
`ScalaAnnotationTypeData` and filter by element kind in `getAnnotationsForType`.

### A13 (MAJOR, confirmed) Field annotations are dropped when the getter has any annotation

`MicronautScalaCompilerPlugin.scala:570`

```scala
if readMethod != null && !readMethod.annotations().isEmpty then
  readMethod.annotations()
else if field != null then
  field.annotations()
```

For `@(Inject @getter) @(Named @field)("x") val foo`, the getter carries
`@Inject`, so `@Named` on the field is discarded. This is an either/or where it
must be a union.

**Fix.** Concatenate getter and field annotations, de-duplicating by name with
the getter taking precedence.

### A14 (MAJOR, confirmed) Overloaded methods collide in the accessor map

`MicronautScalaCompilerPlugin.scala:440`

```scala
val methodByName = LinkedHashMap[String, ScalaMethodData]()
allMethods.foreach { method =>
  if !skipAccessorCandidate(method.symbol) then
    methodByName.put(method.name.toString, methodData(method, constructor = false, owner = symbol))
```

Keyed by simple name, so `def value: String` and `def value(i: Int): String`
collide and the last wins, giving the property the wrong type, modifiers and
annotations. The same loop also computes `methodData` twice per method (again at
`:450`).

**Fix.** Key by name and arity — or register only zero-argument candidates as
read methods and `_=` as write methods — and reuse the computed record.

### A15 (MAJOR, confirmed) Only the primary constructor is modelled

`MicronautScalaCompilerPlugin.scala:462`

```scala
val constructors = List(methodData(template.constr, constructor = true, owner = symbol))
```

Every Scala secondary constructor (`def this(...)`) is dropped, so `@Inject` on
one cannot be honoured and `getDefaultConstructor()` returns empty for a class
whose only no-arg constructor is secondary. The classpath side enumerates all
constructors (`ScalaLoadedClassElement.java:197`) and picks the primary one by a
*different* rule (public-then-fewest-params) than the source side
(`classData.constructors().get(0)`, `ScalaClassElement.java:356`).

**Fix.** Collect `template.body` `DefDef`s whose symbol `isConstructor`, and
align the two primary-constructor selection rules.

### A16 (MAJOR, confirmed) Qualified private/protected mapped as JVM private/protected

`MicronautScalaCompilerPlugin.scala:1600`

```scala
if hasFlag(symbol, Flags.Private) then modifiers.add(ElementModifier.PRIVATE)
if hasFlag(symbol, Flags.Protected) then modifiers.add(ElementModifier.PROTECTED)
```

`private[pkg]` and `protected[pkg]` carry those flags but compile to public JVM
members, so Micronaut refuses to inject or introspect members it could legally
call. `SEALED`, `TRANSIENT`, `VOLATILE`, `SYNCHRONIZED` and `NATIVE` are not
mapped at all on the source path while `ScalaLoadedClassElement.java:512` maps
them faithfully — another source/classpath divergence.

**Fix.** Check `symbol.privateWithin` before emitting `PRIVATE`/`PROTECTED`, and
map the remaining modifiers.

### A17 (MAJOR, confirmed) Every Scala field claims to require reflection and to be private

`ScalaFieldElement.java:73`, `:88`

```java
public boolean isReflectionRequired() {
    return true;
}
```
```java
fieldModifiers.remove(ElementModifier.PUBLIC);
fieldModifiers.remove(ElementModifier.PROTECTED);
fieldModifiers.add(ElementModifier.PRIVATE);
```

`isReflectionRequired(ClassElement callingType)` ignores its argument entirely,
so Micronaut emits reflective field access and GraalVM reflection metadata even
where direct access is possible. Forcing `PRIVATE` means `isPublic()` is false
for every field, `ElementQuery.ALL_FIELDS.modifiers(...)` can never match a
public field, and `PropertyElementQuery` visibility filtering rejects
field-backed properties.

**Fix.** Implement `isReflectionRequired(callingType)` as `!isAccessible(callingType)`,
and keep the real modifier set, special-casing the backing-field case only where
it is actually needed.

### A18 (MAJOR, confirmed) Classpath enumeration uses `getMethods()`/`getFields()`

`ScalaLoadedClassElement.java:224`, `:227`, `:310`

```java
Method[] methods = result.isOnlyDeclared() ? componentType.getDeclaredMethods() : componentType.getMethods();
```

Three defects: the non-declared branch returns public members only (so
protected, package-private and — critically for field injection — private
declared members vanish); it includes `java.lang.Object`'s public methods, which
the source path never produces; and `methodElement`/`fieldElement` pass
`this, this` as owning *and* declaring type, so an inherited method reports the
subclass as its declaring type, defeating inheritance and override logic.

**Fix.** Walk the superclass/interface chain explicitly with `getDeclaredMethods()`/
`getDeclaredFields()`, stop before `java.lang.Object`, and pass the real declaring
element.

---

## B. Robustness, isolation and resource handling

### B1 (MAJOR) One failing visitor aborts the rest, and reports twice

`ScalaProcessingEngine.java:133` catches `Throwable` from `visitor.start()` and
calls `context.fail(...)`, which reports *and throws*. The throw escapes the loop
so no later visitor is started, then `ProcessingState` catches the
`ProcessingException` and reports the identical message a second time. The
comment claims parity with inject-java, but inject-java's reporting methods do
not throw.

**Fix.** Report without throwing and continue to the next visitor.

### B2 (MAJOR) Unhandled exceptions crash the compiler instead of reporting

`ScalaProcessingEngine.java:229` throws `IllegalStateException` for a missing
originating element; the caller (`:196`) catches only `ProcessingException` and
`IOException`, and `ProcessingState.processBeanDefinitions` catches only
`ProcessingException`. The result is a compiler crash stack trace rather than a
diagnostic. `ScalaVisitorContext.sourceClassElement(...).orElseThrow()`
(`ScalaProcessingEngine.java:146`, `:185`) has the same shape and additionally
throws a bare `NoSuchElementException` with no message.

**Fix.** Convert to `ProcessingException` with the offending class element, and
catch `RuntimeException` in the per-class loop.

### B3 (MAJOR) Service files may be lost when a visitor fails

If a visitor calls `context.fail(...)`, the throw skips `processBeanDefinitions()`,
and because dotty's `Phase.isRunnable` is false once errors are reported,
`BeanDefinitionPhase` is skipped too. `context.finish()` and
`BeanDefinitionWriter.finish()` never run, so `META-INF/services` entries are not
written for definitions that *were* generated. Conversely, visitor errors that
only report (without throwing) let generation proceed for a compilation that has
already failed.

**Fix.** Check `ctx.reporter.hasErrors` before generating, and always run
`context.finish()` in a `finally`.

### B4 (MAJOR) Classloader and file-handle leaks

Three unclosed `URLClassLoader`s per compilation: the plugin's own
(`MicronautScalaCompilerPlugin.scala:85`), the visitor-context classpath loader
(`ScalaVisitorContext.java:116`), and the test harness's
(`ScalaCompiler.java:273`). The middle one holds an open handle on every jar of
the compile classpath. In a Gradle Scala compile daemon this is metaspace growth
plus jar-handle exhaustion, and on Windows it prevents deleting jars.

**Fix.** Make `ScalaVisitorContext` `Closeable` and close in `finish()`; cache the
plugin loader statically rather than creating one per `init`; make the harness's
`Compilation` `AutoCloseable`.

### B5 (MAJOR) The "isolated" plugin classloader isolates nothing

`MicronautScalaCompilerPlugin.scala:92`

```scala
private val parentLoadsJavaCompiler = canLoadFromParent("javax.lang.model.element.Modifier")
```

That probe always succeeds (the `java.compiler` module is defined to the platform
loader), so every package in `isParentMicronautApi` is parent-first. The child
loader therefore loads only `io.micronaut.scala.processing.**` plus a handful of
unlisted `io.micronaut.*` packages — a *partial* duplicate of the Micronaut
packages, which is exactly the setup that produces surprising `LinkageError` when
an object crosses the boundary. The jar is already self-contained on the
compiler's plugin loader.

**Fix.** Either drop the child loader, or make it genuinely child-first for all
`io.micronaut.**` and parent-first only for `scala.`/`dotty.`/`java*.`. The
current halfway state has the costs of both.

### B6 (MAJOR) JVM-global system properties and unclear static caches

`ScalaProcessingEngine.java:372` promotes every `micronaut.*` option to a JVM
system property and never restores it, so in a shared Scala compile daemon one
project's `micronaut.processing.*` settings leak into every later compile and
race between concurrent compiles. `AbstractAnnotationMetadataBuilder`'s static
caches are never cleared (inject-java clears them per round), and
`ClasspathAnnotationMetadataReader.CACHE` (`:60`) hands out live
`MutableAnnotationMetadata` instances.

**Fix.** Snapshot and restore the properties around processing; clear the
builder's static caches when the engine finishes; return copies from the ASM
cache.

### B7 (MAJOR) Visitor dispatch is visitor-major, not class-major

`ScalaProcessingEngine.java:139` iterates visitors in the outer loop and classes
in the inner loop; inject-java does the opposite. For classes A, B and visitors
high, low, Java produces `high:A, low:A, high:B, low:B` and Scala produces
`high:A, high:B, low:A, low:B`. Aggregating visitors that accumulate per-class
state behave differently. `ScalaVisitorOrderingSpec` compiles one class and so
cannot distinguish the two.

**Fix.** Invert the loops to match `TypeElementVisitorProcessor`.

### B8 (MAJOR) `new LinkedHashSet<>(sourceClasses.values())` deep-hashes the whole model

`ScalaProcessingEngine.java:142`, `:181`. `ScalaClassData` is a record whose
components include `Object nativeType` — a dotty `tpd.TypeDef`, itself a Scala
case class — so building the set structurally hashes the entire typed tree of
every class, once per visitor plus once for bean definitions, with real recursion
risk on large classes. `sourceClasses` is already a `LinkedHashMap` keyed by
name, so the values are distinct and ordered.

**Fix.** Iterate `sourceClasses.values()` directly, or `List.copyOf(...)` for a
snapshot.

### B9 (MAJOR) `-d` pointing at a jar silently writes to a directory

`MicronautScalaCompilerPlugin.scala:222` takes `ctx.settings.outputDir`'s path and
`mkdirs()` it. `outputDir` may be a jar (`-d out.jar` is supported by scalac), in
which case generated definitions and `META-INF/services` never enter the jar and
the application silently has no beans. The `mkdirs()` result is ignored.

**Fix.** Detect a jar/virtual output and either write through the compiler's
`AbstractFile` API or fail with a clear message.

### B10 (MAJOR) No incremental-compilation story

Bean definitions are written straight into the class output directory behind the
compiler's back, so zinc/Gradle never records them as products: they are not
cleaned when the source bean is deleted, not invalidated when it changes, and on
a partial recompile `META-INF/services/io.micronaut.inject.BeanDefinitionReference`
is regenerated from only the recompiled subset. There is no equivalent of javac's
incremental-annotation-processing registration and no `micronaut.processing.incremental`
handling. Additionally the generation phase runs before `Pickler`, so a failing
compile leaves orphaned `$Foo$Definition.class` files that the next compile can
load — `ScalaVisitorContext.createClassLoader` puts the output directory first on
the visitor classpath.

**Fix.** Verify `DirectoryClassWriterOutputVisitor.finish()`'s merge behaviour
against a pre-existing service file and read-merge-write if it truncates; move
generation after `GenBCode`; document that a clean build is required until this
is properly incremental.

### B11 (MAJOR) Scala `object` is excluded from processing

`MicronautScalaCompilerPlugin.scala:1623` skips every `ModuleClass`, so
`@Singleton object Foo`, `@Factory object Beans` and top-level `given`/`def`s
(which live in the synthetic `Foo$package` module class) produce no metadata at
all. `object` is *the* idiomatic Scala singleton, so this is a first-order
functional gap rather than an edge case. Worse, `collectTree` (`:363`) still
descends into a skipped module class and derives `enclosingTypeName` from
`companionClassName` — the module name with `$` stripped — so a class nested in a
companion-less `object Foo` gets an enclosing type that does not exist.

**Fix.** Decide the policy explicitly. Either map module classes to their
`MODULE$` singleton and support them as beans, or reject them with a clear
diagnostic when they carry a bean stereotype — and in either case stop
synthesising a non-existent enclosing type name.

### B12 (MAJOR) Placeholders and wildcards compare equal to their erasure

`ScalaClassElement.java:731` pins `equalityType()` to `ScalaClassElement.class`
with key `(name, arrayDimensions)`, and the plugin sets a placeholder's `name` to
its erasure (`MicronautScalaCompilerPlugin.scala:1017`). So for `class Repo[T, U]`
the elements for `T`, `U` and a plain `ClassElement` for `java.lang.Object` are
all `equals` with identical hash codes, and Micronaut's aggressive element caching
silently collapses distinct type arguments. Separately,
`ScalaWildcardElement` declares no copy methods, so
`wildcard.withAnnotationMetadata(...)` returns a plain `ScalaClassElement` and
`instanceof WildcardElement` checks in the generics writers fail. And a source
element and a classpath element for the same type are never equal, because
`ScalaLoadedClassElement` keys on the `Class` object.

**Fix.** Give placeholders and wildcards their own `equalityType()`/`equalityKey()`;
add the missing copy overrides to `ScalaWildcardElement`; unify `equalityType()`
across all `ClassElement` implementations to `(name, arrayDimensions)`.

### B13 (MAJOR) Scala type shapes that are modelled incorrectly

| Shape | Current behaviour | Consequence |
| --- | --- | --- |
| by-name `=> T` | `typeData` widens `ExprType` to its result type (`:783`, `:85`) | modelled as `T` while the JVM signature is `Function0`; the generated argument type does not match the bytecode |
| varargs `T*` | no `RepeatedParamType` handling anywhere; `isVarArgs()` not overridden on the source path | the parameter type name is not a JVM type name |
| default arguments | `$default$N` recognised only for annotation members (`:424`) | a Scala default argument is invisible, so the parameter is treated as required |
| value classes (`AnyVal`) | not handled | parameter modelled by its own class while the JVM signature uses the underlying type |
| union types other than `A \| Null` | fall through to the lub's class symbol (`:109`) | silently widened |
| intersection types | truncated to the first bound (`:1083`) | documented in a comment, but not diagnosed |
| `Any`/`AnyRef` in ordinary position | not mapped to `java.lang.Object` | wrong type name |
| trait parameters (`trait Foo(x: Int)`) | only a class's `template.constr` is read | trait constructor parameters are not represented |
| extension methods, `export`, `inline` | not handled | invisible (they live on the skipped module class) |

**Fix.** Handle by-name, varargs and default arguments explicitly (they affect
injection-point correctness); diagnose the rest with a clear "unsupported for
Scala" error rather than silently producing a wrong model.

### B14 (MAJOR) Scala collection converters ignore the element type

`ScalaCollectionConverterRegistrar.java:47` and its siblings register
source/target class pairs but never read the `ConversionContext`, so no element is
converted to the target element type. Injecting `Seq[Duration]` or `List[Int]`
from a `@Value("${x}")` (a `List<String>`) yields a `Seq[String]` typed as
`Seq[Int]` — a `ClassCastException` at first use rather than a conversion error.
There is also no `scala.collection.Map -> java.util.Map` converter and no
converter into `scala.Option` from a non-`Optional` source, and Micronaut does
not chain converters.

**Fix.** Resolve the target element type from `ConversionContext.getFirstTypeVariable()`
and convert elements through the conversion service; add the missing reverse and
scalar converters.

### B15 (MINOR) Smaller items worth folding into the same passes

- `ScalaMethodElement.getOwningType()` returns `declaringType` for "default"
  methods (`:213`), making `withNewOwningType` a no-op for the one case it exists
  for; and `isDefault()` (`:208`) treats every concrete trait method as a Java
  default method.
- `ScalaClassElement.getSyntheticBeanProperties()` (`:250`) returns all
  properties; `ScalaPropertyData` carries no synthetic flag to distinguish them.
- `ScalaEnumElement.elements()` (`:56`) bypasses the element cache, so annotations
  added through `ElementQuery` are invisible through `EnumElement`.
- `ScalaConstructorElement` calls `declaringType.getBeanProperties()` from inside
  a `computeIfAbsent` on an `IdentityHashMap` (`:40`, `ScalaClassElement.java:612`) —
  re-entrant modification during `computeIfAbsent` is undefined behaviour.
- `ScalaLoadedClassElement.getEnclosedElements` has no `PropertyElement` or
  `ClassElement` branch (`:216`), so property queries and nested-class discovery
  return empty for classpath types.
- `LoadedPropertyElement` overrides `getAnnotationMetadata()` but not
  `getAnnotationMetadataToWrite()` (`:908`), so `annotate(...)` writes to metadata
  nobody reads.
- `ScalaPackageElement` never carries package annotations and is reallocated per
  `getPackage()` call.
- `isAssignable` ignores array dimensions and returns true for `java.lang.Object`
  unconditionally, including for primitives (`ScalaClassElement.java:123`).
- Neither implementation overrides `getTypeArguments(String)` / `getAllTypeArguments()`,
  which is how Micronaut answers "what is `T` for the `EventListener<T>` this bean
  implements".
- `isValidDefaultValue` (`ScalaAnnotationMetadataBuilder.java:542`) drops
  empty-string defaults, which are extremely common (`@Named`, `@Property`,
  `@Requires.property`).
- `annotationClassValue` / `enumValue` coerce arbitrary values with
  `String.valueOf` (`:480`, `:502`), producing `AnnotationClassValue("null")` that
  fails only at runtime.
- `normalizeLooseValue` (`:438`) does not handle `ScalaAnnotationData`,
  `ScalaAnnotationData[]` or `ScalaClassValueData[]`, so unresolved members leak
  raw records into metadata.
- Positional annotation arguments are matched against `symbol.info.decls`
  iteration order (`MicronautScalaCompilerPlugin.scala:1325`) rather than the
  primary constructor's parameter list, and invent names like `value1` when the
  annotation type is unresolved (`:1331`).
- `excludedHierarchyType` (`:653`) excludes only `Object` and `Enum`; add
  `scala.Any`, `scala.AnyRef`, `scala.Product`, `scala.Equals`, `java.io.Serializable`.
- Cycle guards in `exceptionMessage` (`MicronautScalaCompilerPlugin.scala:244`,
  `ScalaProcessingEngine.java:426`) only compare against the head exception and
  loop forever on a deeper cycle.
- `optionsHelp` is `None` (`:68`) although options *are* parsed, so
  `-P:micronaut-scala:help` prints nothing and misspelled options are silently
  accepted as `key -> "true"`.

---

## C. Test harness and parity coverage

The corpus is 20 spec files and 220 feature methods, all extending
`AbstractScalaTypeElementSpec`. `DISABLED_TESTS.md` records the shortfall against
Java (194 specs / 1065 features), Groovy (86/473) and Kotlin (19/194). The
harness itself is the limiting factor for several whole categories, so harness
work comes first.

### C1 (MAJOR) Harness capabilities that block parity tests

| Capability | Status | Why it matters |
| --- | --- | --- |
| multi-source compilation | missing — `ScalaCompiler.compile` takes one `source` | separate compilation across files is where phase-ordering and symbol-completion bugs live; nothing currently exercises it |
| joint Java + Scala compilation | missing | Java interop is only tested against *pre-compiled* fixtures, i.e. through `ClasspathAnnotationMetadataReader`, never through the compiler's own Java symbol reader |
| assert compiler warnings | missing — `ScalaCompiler.java:257` consults only `hasErrors()` | `VisitorContext.warn` is entirely untested; zero occurrences of `warn` in the whole spec corpus |
| assert error source position | missing — `errorMessage` (`:263`) joins `Diagnostic::message` only | blocked by A8; once positions exist, tests must be able to assert them |
| compiler options on `buildBeanDefinition`/`buildContext` | missing — only `buildClassElement`/`buildBeanIntrospection` take them | `-Yexplicit-nulls` is therefore never verified at definition or runtime level, only at Element API level |
| `buildBeanDefinition(package, generatedName, source)` | missing — `:141` hardcodes the `$…$Definition` suffix | `$Foo$Intercepted$Definition`, factory definitions etc. cannot be fetched directly; every AOP/factory test is forced through `buildContext` |
| `buildIntroducedBeanDefinition` | missing | worked around via `buildContext` |
| inspect generated bytecode | missing — `Compilation.outputDirectory` is package-private | no test verifies what was actually emitted |

Two of these unlock most of the rest: threading `compilerOptions` through
`buildBeanDefinition`/`buildContext` (a three-line change each) and adding
`compile(List<SourceFile>)` (roughly 25 lines).

### C2 (MAJOR) The harness hides failures

`ScalaCompiler.java:200` swallows `NoSuchMethodException | InvocationTargetException |
InstantiationException | IllegalAccessException` when loading generated bean
definition references, and `loadDefinition` (`:311`) returns `null` on
`ClassNotFoundException`. For a compiler plugin under development this is the
most dangerous default in the codebase: a generated definition with invalid
bytecode or a throwing constructor is dropped, and the test either fails much
later with a confusing `NoSuchBeanException` or *passes* because the assertion was
about a different bean. `buildBeanIntrospection` (`:124`) and `buildClassElement`
(`:93`) likewise collapse "not generated" and "generated but broken" into `null`.

**Fix.** Keep `null` only for `ClassNotFoundException`; rethrow everything else as
`IllegalStateException` naming the target class and cause.

### C3 (MINOR) Harness hygiene

- Every compilation leaks a temp directory; nothing deletes them (no
  `deleteOnExit`/`Files.delete` anywhere in `inject-scala-test/src/main`).
- Three unclosed `URLClassLoader`s per compilation (see B4), in a single JVM
  capped at `-Xmx2048m` with no `forkEvery`.
- `ScalaTypeElementQueryVisitor` uses plain `public static` mutable state while
  every sibling visitor uses a `ThreadLocal` + `withXxx(...)` scope; its `query()`
  is consulted on every compilation regardless of the `enabled` guard.
- `-release:25` is hardcoded at `:253` and cannot be overridden.
- The published test artifact registers **10** always-on `TypeElementVisitor`s
  plus an `AnnotationMapper`, `AnnotationRemapper` and `AnnotationTransformer` in
  its *main* source set, so any consumer putting it on a compile classpath
  activates all of them.
- `ScalaCompiler.compileSource` (`:243`) requires two system properties
  (`micronaut.scala.test.classpath`, `micronaut.scala.plugin.jar`) set only by
  this repository's own build, so the published artifact is unusable externally
  without replicating that wiring by hand.

### C4 Language-feature coverage gaps (each verified by grep over the corpus)

Nothing in the corpus exercises: Scala `object` as a bean (all 10 `object`
occurrences are companions or namespaces — no `@Singleton object`, no
`@Factory object`); varargs; by-name parameters; default arguments; secondary
constructors; `given`/`using` clauses; multiple parameter lists; value classes;
opaque types; extension methods; `lazy val`; `scala.beans.@BeanProperty`;
`case object`; higher-kinded types; tuple types; `Try`/`Either`/`Future` bean
signatures; `@targetName`/`transparent`/`erased`; `private[pkg]`; trait
parameters; `export` clauses; self-types; top-level definitions.

Several of these are the *most idiomatic* Scala constructs, and B11/B13 above
say the implementation gets some of them wrong. They should be tested before, or
alongside, the broader Java/Groovy parity backlog — a Scala user hits `object`
and default arguments on day one and hits `AbstractTypeElementSpec` parity never.

### C5 Mapping to the existing priority buckets

`DISABLED_TESTS.md` already defines P0–P3. This plan does not replace them; it
inserts a **P-1 (Scala-native)** bucket ahead of P0, and reorders the rest by
whether the underlying adapter defect is fixed:

- **P-1 (new, Scala-native):** `object` beans, default arguments, varargs, by-name
  parameters, secondary constructors, `using`/`given` clauses, multiple parameter
  lists, `lazy val`, `@BeanProperty`, trait parameters, value classes, top-level
  definitions. Cheap to write with the existing harness; each pins down a
  currently-unspecified behaviour.
- **P0** (Element API, annotation parity): now largely gated on A4, A5, A6, A7,
  A9, A10, A11, B12. Port the parity specs *after* those land, so the tests
  encode the fixed behaviour rather than the current one.
- **P1** (introspection, bean definition, configuration): gated on A6, A14, A15,
  A17, B14.
- **P2** (AOP, lifecycle, executable): gated on A5, B15's `isDefault`/`getOwningType`
  items.
- **P3** (visitor-generated beans, build-time): gated on B7 and B10.

---

## D. Build, packaging, publication

### D1 (BLOCKER) `micronaut-core` is pinned to an unpublished snapshot

`gradle/libs.versions.toml:5` — `micronaut-core = "5.2.0-SNAPSHOT"`, while
`settings.gradle:34` declares only `mavenCentral()`. `README.md` already
acknowledges this. The consequence beyond "cannot release": all four publishing
workflows (`gradle.yml:111`, `publish-snapshot.yml:39`, `central-sync.yml:45`,
`release.yml:74`) run *without* `-PincludeMicronautCore=true`, unlike the build
step at `gradle.yml:76`, so they cannot resolve the dependency and fail on every
push to `master`. Even if they were fixed, the published POM would declare a
version no consumer can resolve.

**Fix.** Pin a resolvable Core version before merge. Until then, add the snapshot
repository plus the Core include to every publishing workflow, and mark the
module explicitly unpublishable so a green CI does not imply a publishable
artifact.

### D2 (BLOCKER) Fat jar without relocation, plus the same dependencies in the POM

`inject-scala-compiler/build.gradle.kts:77` merges every runtime-classpath jar
except the two Scala stdlib jars, at original package paths with no relocation —
`io.micronaut.**`, `org.objectweb.asm.**`, `org.slf4j.**`. Meanwhile lines 42–54
declare `api(libs.micronaut.core.processor)`, `api(libs.scala3.library)` and
`implementation(libs.asm)`, which become POM `compile`/`runtime` dependencies. A
consumer therefore gets every one of those classes twice, and which copy wins
depends on classpath order.

This also breaks the documented usage. Gradle passes *every file* resolved from
`scalaCompilerPlugins` as its own `-Xplugin:` argument, and sbt's
`addCompilerPlugin` does the same for the `plugin` configuration. Because the POM
is transitive, `dotc` is handed `-Xplugin:` for a dozen jars with no
`plugin.properties`. The only form that works today is
`scalaCompilerPlugins("...") { isTransitive = false }`, documented nowhere.

`src/main/docs/guide/quickStart.adoc:13` compounds it by telling users to put the
same fat jar on the application runtime classpath via `runtimeOnly`, dragging an
un-relocated Micronaut and ASM into the running application.

**Fix.** Choose one model:

- **(a) Thin jar.** Drop the merge; publish real dependencies. Simplest, and it is
  what `inject-scala-test-compiler` already does. Users then need the plugin
  configuration to be intransitive, or the plugin needs a `runtimeClasspath` that
  is genuinely minimal.
- **(b) Shaded jar.** Relocate with the Shadow plugin, merge service files, and
  publish a POM with no compile/runtime dependencies except `scala3-library_3` as
  provided.

Either way, extract a tiny `micronaut-scala-runtime` artifact containing only
`ScalaCollectionConverterRegistrar` and its service file, and point `runtimeOnly`
at that.

### D3 (MAJOR) `DuplicatesStrategy.EXCLUDE` silently drops merged metadata

With `EXCLUDE`, the first entry for a path wins and later ones are dropped with
no message. Concretely: `META-INF/services/**` is **not** merged, so a second
`TypeElementVisitor`/`AnnotationMapper`/`AnnotationTransformer`/`BeanElementVisitor`
descriptor from a bundled jar is lost — and because visitors are discovered by
`ServiceLoader`, a dropped descriptor is a silently missing visitor with no build
error. `META-INF/LICENSE`/`NOTICE` aggregation is broken the same way, which is a
redistribution problem for Apache-2.0 code. Nothing excludes `module-info.class`
(ASM is a modular jar), and the manifest never sets `Multi-Release: true`, so any
copied `META-INF/versions/**` classes are inert. The signature-file exclusion
misses `META-INF/*.EC`, `META-INF/SIG-*` and `META-INF/INDEX.LIST`.

**Fix.** If the fat jar survives D2, use a service-merging transformer, exclude
`module-info.class` and `META-INF/versions/*/module-info.class`, complete the
signature-file exclusions, and set `duplicatesStrategy = FAIL` for everything else
so collisions are loud.

### D4 (MAJOR) The packaging guard never runs and half of it cannot fire

`build.gradle:45`

```groovy
def forbiddenPluginEntries = zipTree(pluginJar).matching {
    include("scala/**")
    include("dotty/**")
    include("scala3-library_3-*")
    include("scala-library-*")
}.files
```

`scala3-library_3-*` and `scala-library-*` are *jar file names*; inside a jar the
entries are class paths, so those two patterns match zero entries under all
circumstances and give false confidence that the filename-based `filterNot` in the
jar task is being cross-checked. Worse, `verifyCompilerArtifacts` is not a
dependency of `check` and is invoked by no workflow — the only packaging guard in
the repository runs only if a human types the task name.

It also does not assert the things most likely to break at release: that the test
POM depends on the plugin by its crossed coordinate rather than the Gradle project
name, that `scala3-compiler_3` does not leak into the plugin POM, that
`plugin.properties` is present at the jar root, or anything about dropped service
files.

**Fix.** Delete the two dead patterns; assert on the *inputs*
(`configurations.runtimeClasspath` must contain no `scala3-compiler_3` /
`scala3-library_3` / `scala-library` after filtering); add
`include("META-INF/versions/*/scala/**")`; add the four missing assertions; and
wire `check.dependsOn("verifyCompilerArtifacts")`.

### D5 (MAJOR) The Scala toolchain pin is incomplete

`inject-scala-compiler/build.gradle.kts:59` pins only `scala3-library_3` and
`scala3-compiler_3`. The rest of the toolchain that `scala3-compiler_3` drags in
is unpinned: `tasty-core_3`, `scala3-interfaces`, `scala-asm`, `compiler-interface`,
and the 2.13 `scala-library`. A bump elsewhere can produce a `tasty-core_3` that
does not match the pinned compiler — exactly the ABI mismatch the pin exists to
prevent.

Separately, `exclude(group = "io.micronaut.sourcegen", module = "micronaut-sourcegen-bom")`
(`:44`) removes *version constraints*, not sourcegen itself, leaving its
transitive versions to unmanaged conflict resolution and then bundling the result.

**Fix.** Pin the whole `org.scala-lang` group; exclude the specific Scala BOM
rather than the sourcegen platform, and comment which versions that is expected
to move.

### D6 (MAJOR) CI runs one Java version, and not the one the artifact targets

Every workflow pins `java: ['25']`, while `inject-scala-compiler/build.gradle.kts:70`
targets `-release:17` and the harness passes `-release:25` to `dotc`. The shipped
artifact claims Java 17 compatibility and is never compiled against, run on, or
tested with a 17 or 21 runtime — despite the plugin doing `--add-modules=java.compiler`
work and reflectively probing `javax.lang.model`, both of which are
JDK-version-sensitive.

`verifyCompilerArtifacts` runs in no workflow (D4). And nothing tests the plugin
as an end user consumes it: there is no `doc-examples/`, no sample application,
and the `functional-test` convention plugin in `buildSrc` is applied by zero
projects. Every test drives `dotc` with `-Xplugin:<absolute path to the built jar>`,
which bypasses coordinate resolution and `-Xplugin:` fan-out — precisely the two
things D2 shows are broken.

**Fix.** Add 17 and 21 to the matrix (at minimum a 17 run of the test task); wire
`verifyCompilerArtifacts` into CI; add a functional test that resolves the
published coordinate through a real `scalaCompilerPlugins` configuration and
compiles one `@Singleton` Scala class, plus an sbt equivalent.

### D7 (MAJOR) Template residue

This repository was generated from the Micronaut module template and a
significant amount of template-only content is now wrong:

- `AGENTS.md:3` still says *"This repository is the Micronaut template for
  generated module repositories"* and describes `project-template/`,
  `project-template-bom/`, `io.micronaut.build.internal.project-template-*.gradle`
  and `template-cleanup.yml` — none of which exist here. The whole file needs
  rewriting for the real shape (`inject-scala`, `inject-scala-compiler`,
  `inject-scala-test`, `inject-scala-test-compiler`, `micronaut-scala-bom`).
- `CONTRIBUTING.md:19` claims the parity tests require Docker — they do not;
  `ScalaCompiler` runs `dotty.tools.dotc.Main` in-process. Docker is needed only by
  the vulnerability-audit script. The `TESTCONTAINERS_RYUK_DISABLED` env in
  `gradle.yml:25` is the same residue.
- `CONTRIBUTING.md:88` tells maintainers to update `githubCoreBranch` in
  `gradle.properties`; no such property exists (the real knobs are
  `local.git.micronaut-core`, `includeMicronautCore`, `micronautCoreBranch`), and
  the file documents those correctly 40 lines earlier.
- `README.md:1` retains the template checklist HTML comment and a commented-out
  Examples section pointing at a non-existent `examples/`.
- `config/checkstyle/suppressions.xml:11` suppresses `.*doc-examples.*`, which does
  not exist; the commented block above names micronaut-core files.
- `buildSrc/.../convention-base.gradle` is a verbatim copy of micronaut-core's:
  Spotless excludes reference core files absent here, `jvmArgs "--enable-preview"`
  is core-specific, and `inputs.file("config/accepted-api-changes.json")` names a
  file that does not exist and will fail the first 1.0.x release build once
  japicmp is enabled.
- `convention-geb-base.gradle` and `functional-test.gradle` are applied by no
  project, keeping `geb`, `htmlunit`, `selenium` (6 aliases) and
  `native-gradle-plugin` alive in the version catalog for Renovate to churn on.
- `files-sync.yml`, `update-gradle-wrapper.yml` and `skills-validation.yml` are
  gated on `github.repository == 'micronaut-projects/micronaut-project-template'`
  and no-op forever; `scala` is not in the `files-sync.yml` repository list, so the
  "do not edit this file directly" headers on seven workflows are misleading here.
- `graalvm-latest.yml` / `graalvm-dev.yml` build a native-test matrix for a
  repository with no native tests, and `introduction.adoc:28` says native is out
  of scope.
- `sonatype.yml` triggers only on PRs to `[0-9]+.[0-9]+.x`, but the default branch
  is `master`, so the vulnerability audit never runs on a PR.

### D8 (MAJOR) The multi-Scala-version story is not built for

`SCALA3_SUPPORT_PLAN.md:17` requires a parallel variant per Scala release, but
adding one today is a hand-edit across seven places: a single `scala3` catalog
alias read by four build files; two new Gradle projects that cannot be named after
their version (`settings.gradle:47` explains that `TYPESAFE_PROJECT_ACCESSORS`
rejects dots in project names); `verifyCompilerArtifacts` hardcoding one version
and two project paths; the BOM's hand-maintained `extraExcludedProjects` list
(8 entries for 4 projects) and hand-added constraints; `parentModuleId` becoming
ambiguous with two self-declared root modules; duplicated Checkstyle/Spotless/
NullAway/Javadoc runs over the same shared sources; and 20 hardcoded `3.9.0`
occurrences across README, plan, CONTRIBUTING and the guide.

A BOM listing two mutually exclusive ABI variants under one `propertyName = "scala"`
is also semantically wrong — consumers would get both.

**Fix.** Introduce a declared list of Scala variants and generate the compiler
subprojects in `settings.gradle` from it (mangling `.` to `_` in project names),
with a shared convention plugin that reads a `scalaVersion` project property and
owns the per-variant `verifyCompilerArtifacts`. Publish one BOM per variant, or
drop the plugin constraints from the BOM entirely.

---

## E. Documentation

### E1 What exists

Four guide pages totalling about 110 lines, plus `toc.yml`:

- `introduction.adoc` — what the project is, supported Scala version, the two
  coordinates, a prose feature list, and a pointer to "the Scala language-support
  documentation in the Micronaut Core repository" for actual usage.
- `quickStart.adoc` — the only usage page. Two lines of Gradle, a bare Maven
  `<dependency>` block, and three sentences of prose for sbt and Mill with no code.
- `repository.adoc` — contributor-facing description of the five Gradle projects.
- `releaseHistory.adoc` — release policy prose, no history.

`README.md` is maintainer-facing. There is no working example anywhere, no
runtime-dependency guidance, no option reference, no testing guidance and no
limitations page.

### E2 Defects in what exists

- **sbt/Mill advice double-suffixes.** `quickStart.adoc:41` says to use
  `CrossVersion.full` / Mill's `:::`, while every coordinate printed elsewhere
  already contains `_3.9.0`. Following both yields
  `micronaut-inject-scala_3.9.0_3.9.0`. The page must pick one form and mark the
  other as wrong.
- **The Maven section cannot work.** `quickStart.adoc:25` shows a plain
  `<dependency>`; registering a Scala compiler plugin in Maven needs
  `scala-maven-plugin` with `<compilerPlugins>` or an explicit `<arg>-Xplugin:…</arg>`.
  As written the plugin never runs and no bean definitions are generated — a silent
  failure.
- **The `-Xplugin:` fan-out trap is undocumented** (see D2).
- Contributor bootstrap instructions (`-Plocal.git.micronaut-core`) are mixed into
  the user quick start, and the examples use `1.0.0-SNAPSHOT`.

### E3 Facts a doc author needs (verified from source)

- Group `io.micronaut.scala`; plugin `micronaut-inject-scala_3.9.0`; test support
  `micronaut-inject-scala-test_3.9.0`; BOM `micronaut-scala-bom`; version
  `1.0.0-SNAPSHOT`; Scala `3.9.0`, requiring `org.scala-lang:scala3-compiler_3:3.9.0`.
- Compiler-plugin name is **`micronaut-scala`**
  (`MicronautScalaCompilerPlugin.scala:66`), registered through
  `inject-scala/src/main/resources/plugin.properties`
  (`pluginClass=io.micronaut.scala.processing.MicronautScalaCompilerPlugin`).
- Option prefix is therefore `-P:micronaut-scala:<key>=<value>`. There is no
  literal `-P:` string in the repository; the prefix is the Scala 3 default derived
  from the plugin name. Options are split on the first `=`, and a bare token
  becomes `key -> "true"` (`:167`).
- `ScalaProcessingEngine.setMicronautOptionsAsSystemProperties` (`:372`) promotes
  every key starting with `micronaut` to a system property. This is the Scala
  equivalent of javac's `-Amicronaut.processing.*` flags, so the documented forms
  are `-P:micronaut-scala:micronaut.processing.group=…`,
  `…module=…`, `…incremental=true`.
- Output directory and classpath come from the compiler settings
  (`ctx.settings.outputDir`, `ctx.settings.classpath`), not from options.
- The plugin inserts two phases, useful for `-Xprint` when troubleshooting:
  `micronaut-scala-type-visitors` (after `PostTyper`) and
  `micronaut-scala-bean-definitions` (before `Pickler`).
- Processing failures surface as dotc errors formatted
  `"Error processing Scala element[<name>]: <message>"` (`:241`).
- The only genuine runtime contribution is `ScalaCollectionConverterRegistrar`,
  registered via `META-INF/services/io.micronaut.core.convert.TypeConverterRegistrar`.
  Without it, Scala collection injection and Scala collection configuration
  binding do not convert.
- **Add-on processors go on the compiler's `-classpath`, not on an
  `annotationProcessor` configuration**, because visitors are discovered by
  `ServiceLoader` over the compile classpath. This is a materially different mental
  model from javac/kapt and needs its own section. The build demonstrates it at
  `inject-scala-test-compiler/build.gradle.kts:55`.
- The plugin jar is built `-release:17`; the build and tests only run on JDK 25.
- Scala 2 is out of scope.

### E4 Target documentation set

New `toc.yml`:

`introduction` · `quickStart` · `buildTools` (Gradle, sbt, Mill, Maven — complete
working build files for each) · `configuration` (the `-P:micronaut-scala:` option
reference) · `dependencies` (compile vs. runtime vs. classpath-visible visitors;
the runtime converter registrar) · `testing` (the test artifact and its required
system properties) · `limitations` (distilled from `DISABLED_TESTS.md` and B11/B13)
· `troubleshooting` (plugin not applied, no bean definitions generated,
`-Xplugin:` fan-out, `java.compiler` module, version-suffix mismatch) ·
`repository` · `releaseHistory`.

---

## F. Sequenced work plan

Each wave is independently mergeable and leaves the build green.

### Wave 0 — make the build honest (prerequisite)

1. Wire `verifyCompilerArtifacts` into `check` and CI; delete its two dead
   assertions; add the four missing ones (D4).
2. Add JDK 17 and 21 to the CI matrix (D6).
3. Add `-PincludeMicronautCore=true` to the publishing workflows, or mark the
   module unpublishable until Core is pinned (D1).
4. Rewrite `AGENTS.md` for this repository; fix the `CONTRIBUTING.md` Docker and
   `githubCoreBranch` claims; remove template residue from `README.md`,
   `suppressions.xml` and `buildSrc` (D7).

Rationale: without this, later waves cannot tell a real regression from an
already-broken guard.

### Wave 1 — confirmed correctness defects

5. `hasAllFlags` and the four composite-flag call sites (A1), with a regression
   test asserting a Scala class extending a Java class reports it as a super type.
6. Drive the pipeline from `runOn` and key state per `Run` (A2, A3); make
   `processBeanDefinitions` depend on `processTypeVisitors` having run.
7. Widen the reporter to carry native elements and resolve `srcPos` (A8); extend
   the harness to expose diagnostics with positions and warnings (C1).
8. Stop fabricating annotation values from printed tree text; report an error
   instead (A9). Remove the `classOf`-substring scan.
9. `withTypeArguments` (A4), `overrides`/`hides` (A5).
10. Field/method inheritance from classpath supertypes (A6).

### Wave 2 — harness, then Scala-native tests

11. `compile(List<SourceFile>)`, `compilerOptions` on `buildBeanDefinition`/
    `buildContext`, the three-arg `buildBeanDefinition`, `buildIntroducedBeanDefinition`
    (C1). Stop swallowing load failures (C2). Clean up temp dirs and classloaders
    (C3, B4).
12. Write the P-1 Scala-native specs (C4/C5), starting with `object` beans,
    default arguments, varargs, by-name parameters and secondary constructors —
    these will fail, and the failures define the next fixes.
13. Fix B11 (`object` policy), B13 (by-name, varargs, defaults), A15 (secondary
    constructors), A14 (accessor collisions), A16 (qualified access), A17 (field
    modifiers and reflection).

### Wave 3 — annotation metadata unification

14. Route classpath elements through `ScalaAnnotationMetadataBuilder` (A7).
15. Resolve annotation defaults from symbols and add `visitAnnotationDefault` to
    the ASM reader (A10).
16. `getAnnotationMirror` fallback by name (A11); retention default and guard,
    `@Target` capture (A12); getter/field annotation union (A13); the
    `normalizeLooseValue`/`annotationClassValue`/`enumValue` items in B15.
17. Then port the P0 parity specs, which now encode fixed behaviour.

### Wave 4 — element model consistency

18. Equality and copy semantics for placeholders, wildcards and loaded elements
    (B12); `getTypeArguments(String)`/`getAllTypeArguments()`; the remaining B15
    items.
19. Classpath enumeration via the declared-member walk (A18).
20. Collection converters with element-type conversion (B14).
21. Then port P1 and P2 parity specs.

### Wave 5 — packaging, runtime artifact, docs

22. Resolve the fat-jar question (D2) and, whichever way it goes, extract
    `micronaut-scala-runtime` for the converter registrar.
23. Service-file merging and the remaining jar hygiene items (D3); complete the
    Scala toolchain pin (D5).
24. Add the end-user functional test (Gradle, and sbt if practical) (D6).
25. Write the documentation set in E4.

### Wave 6 — structural

26. Incremental compilation: service-file merge behaviour, generated-file
    registration, output-after-`GenBCode` (B10).
27. Multi-Scala-variant build layout (D8).
28. Visitor dispatch order and the remaining engine hygiene items (B1, B2, B3,
    B6, B7, B8, B9).

---

## G. Open questions to settle with a test

These were identified by review but need execution to confirm:

1. Does dotty synthesise `x_=` setter symbols with `Flags.Accessor` before
   `Pickler`? If not, `isPropertySetterDeclaration`
   (`MicronautScalaCompilerPlugin.scala:565`) never finds a write method and every
   extracted property is read-only, breaking setter injection.
2. Does `DirectoryClassWriterOutputVisitor.finish()` merge with a pre-existing
   service file, or truncate it? This decides how bad B10 is in practice.
3. What are the declared-vs-inherited semantics of the `TypeElementQuery` SPI that
   `ScalaProcessingEngine.java:287` uses `ElementQuery.ALL_FIELD_AND_METHODS` for?
   If declared-only is intended, it needs `.onlyDeclared()`.
4. Does `report.inform` print without `-verbose`? `VisitorContext.info(...)` is
   expected by Micronaut visitors to be user-visible.
