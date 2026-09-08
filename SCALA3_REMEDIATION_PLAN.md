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

### A3 (WITHDRAWN -- not a defect) State is not keyed per `Run`

This finding was wrong and no fix is needed. Re-checked against the 3.9.0
compiler sources while implementing A2.

The claim was that plugin phase instances are memoised in `ContextBase`, so the
`ProcessingState` booleans would already be `true` for the second `Run` that
`Driver.finish` creates for macro-suspended units, and those units would be
collected and silently discarded.

What is memoised on `ContextBase` is the `Plugin` *instance* (`Plugins._plugins`),
not the phases or their state. `Run.compileUnits` calls
`ctx.base.addPluginPhases(...)` (`Run.scala:379`), which calls
`plug.initialize(options)` -> `init(options)` on every run, and this plugin's
`init` (`MicronautScalaCompilerPlugin.scala:73`) constructs a fresh
`MicronautScalaPluginClassLoader`, a fresh `MicronautScalaCompilerPluginImpl` and
therefore a fresh `ProcessingState` and `ScalaProcessingEngine` each time. The
second `Run` starts clean.

The genuine costs in this area are a new classloader and a fully reloaded
Micronaut adapter per run -- see B4 (classloader leaks) and B6 (static caches),
which remain open. The correction does not affect A2, which was confirmed and is
fixed independently.

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

### A6 (BLOCKER, confirmed, done) Inherited members from classpath supertypes are invisible

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

**Fix.** Mirror the method walk for fields, honouring `isOnlyDeclared()` — **done**.
Fields are de-duplicated by name, and an inherited field's type is resolved against
the parameterisation the subtype used.

The classpath half — falling back to `visitorContext.getClassElement(type.name())`
and merging its enclosed elements — is **done**, on the fourth attempt. The three
before it were each implemented, tested and reverted, and all three failed the same
way:

    Element of type [class io.micronaut.inject.ast.MethodElement$1] does not
    support adding annotations at compilation time

That message was read twice as Core handing out immutable elements of its own —
first synthetic elements from `MethodElement.of(...)`, then reflective ones from
the element factory — and the item was filed under **F2. Blocked on Micronaut
Core**. Only the reflective half of that was real. It was
`ScalaLoadedClassElement` resolving generics through `ClassElement.of(...)`, which
hands back Core's immutable `ReflectGenericPlaceholderElement`; the loaded model now
builds this repository's own placeholder and wildcard elements (`101e31b`), and six
failures became five.

The rest was a misreading, and the exception is misleading by construction.
`MethodElement$1` is **not** the anonymous element `MethodElement.of(...)` returns.
It is the anonymous `MutableAnnotationMetadataDelegate` created by the **default**
`MethodElement.getMethodAnnotationMetadata()`
(`core-processor/.../inject/ast/MethodElement.java:61`), which overrides only the
read side and inherits a throwing `annotate`. The exception calls `getClass()` from
inside that delegate, so it names the delegate and can never name the element at
fault.

The element at fault was this repository's own `LoadedMethodElement` — and
`LoadedConstructorElement` beside it. Both extend `AbstractScalaElement`, so
`Element.annotate(...)` worked; neither overrode `getMethodAnnotationMetadata()`, so
that call fell through to Core's read-only default and threw. `ScalaMethodElement`
does override it (`ScalaMethodElement.java:119`), which is exactly why only the
specs annotating a member inherited from a *classpath* supertype failed while the
same visitor over a source supertype passed. The override was added while fixing
`LoadedPropertyElement` in B15, and with it the merge lands.

**The general rule for this plugin:** `MethodElement` has two independent mutable
surfaces — `getAnnotationMetadata()`/`Element.annotate(...)` for the class+method
hierarchy, and `getMethodAnnotationMetadata()` for the method-only metadata.
Nothing in the type system requires overriding the second, and the failure names
the wrong class when you do not. Any element a visitor can reach must override
`getMethodAnnotationMetadata()`, not just `annotate`. Every shipped Micronaut
language module does, through the public helper
`io.micronaut.inject.ast.annotation.MethodElementAnnotationsHelper`:
`JavaMethodElement.java:111`, `GroovyMethodElement.java:90`,
`AbstractKotlinMethodElement.kt:52`, `PythonMethodElement.java:181`. The call sites
that reach it are ordinary ones — `ConfigurationMetadataWriterVisitor.java:243`
annotates every `@ConfigurationProperties` accessor through it, inherited ones
included, and `VisitorUtils.java:300` reads through the same surface.

A7 and A18 were genuine prerequisites — A7 fixed what classpath annotation metadata
*contains*, A18 fixed which members are *enumerated*, and neither addresses
mutability. A further prerequisite came from B15: universal supertypes are excluded
from the merge, because a Scala class on the classpath really does implement
`scala.Product`, `scala.Equals` and `java.io.Serializable`, but the source path
never produces their members and none of them can carry Micronaut metadata, so
merging them in would make the two element kinds disagree about what a class
inherits.

Pinned by `ScalaClasspathInheritanceSpec`: methods and fields of every visibility
are inherited, `onlyDeclared` still stops at the boundary, the universal supertypes
stay out, an inherited classpath method can be annotated, and its
`getMethodAnnotationMetadata()`/`getDeclaredMethodAnnotationMetadata()` answer with
the method's own declaration rather than the class's.
`getDeclaredMethodAnnotationMetadata()` reads through `getMethodAnnotationMetadata()`,
so it was suspect too; on these elements it happened to be right already, because
they never built a class+method hierarchy for the default to narrow.

**A separate defect found while verifying this, fixed with it.** An inherited method
was owned by the supertype that declares it rather than by the class the query was
made on. `getDeclaringType()` answers where a method is declared and
`getOwningType()` answers which class it was reached through; Core's Java module
builds every enclosed element owned by the queried class
(`JavaClassElement.java:1008`), and both element kinds here kept the supertype.
Anything resolving against the owner therefore read the supertype's answer:
`ConfigurationUtils.buildPropertyPath` falls back to the owning type for a
declaring type that is not itself a `@ConfigurationProperties`, so an inherited
accessor took its prefix from the supertype, which carries none, and bound
`.host` instead of `app.host` — silently, with no error and an unset property. This
was **not** a regression from the classpath merge: the source path was equally
wrong, which is why the merge reproduced it faithfully. Inherited methods are now
re-owned through `MethodElement.withNewOwningType`. Fields are not: Core declares no
equivalent on `FieldElement`, so an inherited field still reports its declaring type
as its owner.

Note also that Scala does not permit field shadowing — neither `override var` nor
a `private var` of the same name compiles when the superclass field is visible —
so the shadowing rule is only reachable through a Java or classpath supertype.

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

**Fix — done for declared annotations.** The ASM reader now caches the *raw*
annotation values rather than finished metadata -- resolution depends on the
annotation types of the compilation currently running, and that cache is shared
across compilations -- and `LoadedAnnotatedElementData` adapts them into
`ScalaAnnotationData`, resolving each annotation type through the compiler (A11)
rather than by re-reading class files. `ScalaLoadedClassElement` then builds its
class, method, constructor, field and parameter metadata through
`ScalaAnnotationMetadataBuilder`, so both element kinds share one pipeline.

Reproduced before fixing: `@ExternalInheritedSingleton` on a classpath type gave
`hasStereotype(SCOPE) == false` while the identical annotation on a source type
gave true. Pinned by `ScalaClasspathMetadataSpec`, which asserts both that the
stereotype resolves and that the two element kinds agree.

Still open: annotations *inherited* from a classpath supertype. `buildHierarchy`
only walks a hierarchy for `ScalaClassData`, and a loaded element is adapted as a
flat member, so this covers declared annotations only. A6's classpath half now
merges the *members* of such a supertype, but each still carries only the
annotations declared on it.

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

**Fix — done, in two parts, and not the way the finding proposes.**

Defaults for annotations declared in this compilation are now collected from
*every* unit before any unit is extracted, which the move to `runOn` made
possible. Previously an annotation picked up its defaults only when it was
declared in the same file as its use.

Resolving defaults from the annotation *symbol* does **not** work for classpath
annotations, contrary to the finding: dotty's classfile parser records only a
marker for the attribute and discards the value
(`ClassfileParser.scala:998` adds `defn.AnnotationDefaultAnnot` with no value), so
the value is not on the symbol to be read. Only the other half of the proposed fix
reaches them, and that is what is implemented -- `visitAnnotationDefault` in the
ASM reader, with the defaults merged into the annotation type when it is
registered with the metadata builder. That is the single point both extracted and
on-demand-resolved types pass through.

Note the two kinds surface differently, and both are correct: Scala passes a
synthetic default-getter argument for each omitted member, so a Scala annotation's
defaults are resolved into the annotation's *values*, while a Java annotation's
stay in its default values -- which is what inject-java produces too.

Pinned by `ScalaAnnotationDefaultsSpec`.

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

**Fix — done.** The plugin now exposes `resolveAnnotationType(name)`, which looks the
type up through the dotty context and builds a `ScalaAnnotationTypeData` from the
symbol. The builder calls it whenever a name is not already in
`nativeAnnotationTypes`, registering the result so the type's own meta-annotations
and members are pulled in, and remembering names that do not resolve so the
compiler is asked only once each.

Reproduced before fixing: a visitor adding `@Location` -- a fixture annotation
meta-annotated `@Qualifier` -- to a class in a compilation that never mentions it
gave `hasStereotype(QUALIFIER) == false`. It is now true, and the annotation is
additionally wrapped in its `@Repeatable` container, which also depended on the
mirror. Pinned by `ScalaAnnotationMirrorSpec`.

Member defaults are not available through this path, since those are harvested per
compilation unit -- that is A10, still open.

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

**Fix — retention done, `@Target` still open.** `valueOf` is guarded, and the
default is now `CLASS` *for Java annotation types only*.

Defaulting everything to `CLASS`, as the finding says, is wrong and breaks a real
case: a Scala annotation is a class extending `StaticAnnotation`, not a Java
annotation type, and has no JLS retention at all -- it exists purely as
compile-time metadata that Micronaut bakes into the generated definition. Applying
the JLS default to it made `@Qualifier class MyQualifier extends StaticAnnotation`
stop being a qualifier, which the existing suite caught. `ScalaAnnotationTypeData`
therefore carries `javaDefined`, and only Java annotation types get `CLASS`.

Pinned by `ScalaRetentionSpec`, which asserts all three: a Java annotation with no
retention is not written into the bean definition, one declaring `RUNTIME` is, and
a Scala annotation with no retention still reaches the runtime.

`@Target` is still not captured. That remains worth doing -- it matters more in
Scala than in Java, because Scala copies an annotation on a `val` onto the field,
the getter and the constructor parameter unless meta-annotations restrict it.

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

**Fix — applied, but the described symptom does not reproduce.** The two lists are
now unioned, de-duplicated by name with the getter winning a clash, so the record
holds what it claims to.

Nothing observable changed, and on inspection nothing could: `ScalaPropertyElement`
builds its metadata through Micronaut's own `PropertyElementAnnotationMetadata`,
which already composes the read method, the write method *and* the field. The
either/or in `propertyAnnotations` was therefore masked on every public path --
`getBeanProperties()` and `getEnclosedElements(PropertyElement)` alike, both
checked against the previous code.

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

**Fix — applied, but the described symptom does not reproduce.** The accessor map
now takes only accessor-shaped methods (zero-argument reads, one-argument `_=`
writes) and the record is computed once instead of twice per method.

The collision itself could not be reproduced, and on reading the code it cannot
occur today: the `declarations` pass runs *after* the body pass and re-registers
the accessor for anything that can become a property, overwriting whatever an
overload put there. `isPropertyDeclaration` requires a non-`Method` symbol, so a
`def`-only property never reaches the property model at all. The narrowing is
therefore a guard against a latent defect rather than a fix for a live one; the
duplicate `methodData` call it removes was real.

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

**Fix — done, but the finding was half wrong.** Checked against the emitted
bytecode rather than assumed:

| declaration | bytecode | was reported |
| --- | --- | --- |
| `private def` | private | private (correct) |
| `private[pkg] def` | public | public (already correct) |
| `protected def` | **public** | protected (wrong) |
| `protected[pkg] def` | **public** | protected (wrong) |

`private[pkg]` was never affected: dotty leaves `Flags.Private` unset for a
qualified private, so no `privateWithin` check is needed. The real defect is
wider than the finding says — *plain* `protected` is also emitted as public,
because Scala's `protected` means subclass-only and JVM `protected` additionally
grants package access, so scalac emits public and enforces at compile time.
`PROTECTED` is now emitted only for a `JavaDefined` symbol, which really is
JVM-protected. `SEALED` is now mapped too.

`TRANSIENT`, `VOLATILE`, `SYNCHRONIZED` and `NATIVE` are still unmapped: in Scala
these come from `@transient`/`@volatile` annotations rather than symbol flags, so
they need their own verification against emitted bytecode before being mapped.

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

**Fix — done.** `isReflectionRequired(callingType)` is now
`!isAccessible(callingType)`, and `ScalaFieldElement` keeps the modifier set it is
given. The backing-field rule moved into the extractor, where it belongs: a Scala
`val`/`var` really is emitted as a private field plus accessors whatever the
declaration's visibility says, but that is a fact about *those* declarations, not
about fields in general — and forcing it in the element made every synthetic field
private too, which is what blocked `object` support in B11.

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

**Fix — done.** The hierarchy is walked explicitly with `getDeclaredMethods()`/
`getDeclaredFields()`, stopping before `java.lang.Object`, de-duplicating methods by
name and erased parameter types and fields by name, and each element is given the
class element that actually declares it. Constructors always use
`getDeclaredConstructors()`, since constructors are not inherited and the previous
non-declared branch was additionally hiding the non-public ones.

Measured before and after on a two-level classpath fixture. Before: `ALL_METHODS`
returned two of the four real methods plus eight `java.lang.Object` methods, all
claiming the queried class as their declaring type; `ALL_FIELDS` returned *one*
field -- the public inherited one -- omitting even the class's own private field,
which is what made field injection on a classpath type impossible. After: all four
methods with their real declaring types, no `Object` methods, and all four fields.

Pinned by `ScalaClasspathEnumerationSpec`; all four cases fail against the previous
code.

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

**Fix — done; objects are supported as beans.** A module class carrying a
user-written annotation is now extracted. An `object` compiles to a class with a
*private* constructor plus a public static `MODULE$` holding the one instance
(verified from the emitted bytecode), so Micronaut cannot construct it: it is
modelled as a `@Factory` whose synthetic `MODULE$` field carries the object's own
annotations and produces the bean. The injected bean is therefore the same
instance Scala code reaches through `Config`, which a constructed bean would not
be.

Two things this required. `DeclaredBeanElementCreator` always emits the factory
class's own bean definition, which here has the same bean type as the produced one
and would be built through the private constructor, so resolution failed with
`NonUniqueBeanException`; that one definition is suppressed for module classes.
And the gate cannot be "has annotations": dotty attaches
`scala.annotation.internal.SourceFile` to *every* module class, so an unfiltered
check treats every companion object in the compilation as a bean declaration.

The `companionClassName` half of this finding is **not** a defect and was left
alone — companion-less objects legitimately enclose their nested classes, and
guarding it breaks their bean introspection.

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

**Fix — placeholders and wildcards done; loaded elements still open.**

Both halves reproduced. For `class Repo[T, U]` the elements for `T` and `U` were
`equals` with identical hash codes, and equal to a plain `java.lang.Object`;
`wildcard.withAnnotationMetadata(...)` returned a plain `ScalaClassElement`.
Placeholders are now keyed on the type variable, wildcards on their bounds, and
`ScalaWildcardElement` keeps its type across an annotation copy.

One correction to the finding: `withArrayDimensions` must **not** be added to the
wildcard, and the annotation copy has to erase when the element carries array
dimensions. `Array[_]` is modelled as a single type carrying both the wildcard
bounds and the dimension, and an array of a wildcard is not itself a wildcard --
`Array[_]` is `Object[]`. Adding the copy override without that rule broke
`ScalaBeanIntrospectionSpec`, which is now guarded by a test of its own.

**Done.** A source element and a classpath element for the same type now compare
equal. Reproduced first: `context.getClassElement("...ExternalBase")` and the
supertype of a source class extending it were two elements with different hash
codes, because the classpath element inherited the base key -- its `Class` object
-- while the source element keyed on `(name, arrayDimensions)`. Micronaut caches
by element identity, so whichever route ran first decided what the other saw. Both
now use the same `equalityType()` and the same key; placeholders and wildcards
keep their own, since their identity is the variable or the bounds rather than the
erasure. Pinned by a case in `ScalaElementIdentitySpec`.

### B13 (MAJOR) Scala type shapes that are modelled incorrectly

| Shape | Current behaviour | Consequence |
| --- | --- | --- |
| by-name `=> T` | **fixed** — modelled as `scala.Function0` | — |
| varargs `T*` | **not a defect** — see below | — |
| default arguments | **blocked on a Core SPI change** — see below | a Scala default argument is invisible, so the parameter is treated as required |
| value classes (`AnyVal`) | **fixed** — unboxed at the top level, boxed when nested | — |
| union types other than `A \| Null` | **fixed** — erased as the JVM does | — |
| intersection types | **fixed** — erased as the JVM does | — |
| `Any`/`AnyRef` in ordinary position | **fixed** — `Any` and `AnyVal` map to `java.lang.Object`; `AnyRef` already did | — |
| trait parameters (`trait Foo(x: Int)`) | **not a defect** — see below | — |
| extension methods, `export`, `inline` | **not a defect** — see below | — |

**Varargs — not a defect.** Checked against emitted bytecode: `def f(xs: String*)`
emits a single method taking `scala.collection.immutable.Seq` with `ACC_VARARGS`
*unset*, because Scala varargs are `Seq`-based rather than array-based. So the
current model is already right on both counts — the parameter type name is a real
JVM type name, and leaving `isVarArgs()` false is correct. (`@scala.annotation.varargs`
additionally emits an array overload with `ACC_VARARGS` set; that forwarder is
generated in the backend and so is not visible to this phase. The `Seq` method is
the real one, so nothing is lost.) Already covered by
`ScalaLanguageFeatureSpec`.

**Default arguments — blocked on Core.** Micronaut does model optional
parameters, but only through `io.micronaut.inject.ast.KotlinParameterElement`,
and `MethodGenUtils` generates *Kotlin's* calling convention for them: a bitmask
plus a synthetic `$default` overload. Scala's convention is unrelated — a
`foo$default$N()` getter per parameter, no mask and no overload — so implementing
that interface would make Core emit calls to methods that do not exist. Honouring
Scala defaults at injection points needs a language-neutral SPI in Core, or a
Scala equivalent of the Kotlin one. This is out of scope for this repository and
is tracked in **F2. Blocked on Micronaut Core**, with an investigation open
against micronaut-core.

**Unions, intersections and `Any` — fixed, by erasing rather than diagnosing.**
Diagnosing was the wrong prescription: these types have a perfectly well defined JVM
signature, so the model can simply report it. Measured with `javap` on dotty 3.9.0
output before changing anything:

| declared | JVM signature | model said |
| --- | --- | --- |
| `String \| Int` | `java.lang.Object` | `scala.Matchable` |
| `String \| CharSequence` | `java.lang.CharSequence` | `scala.Matchable` |
| `Alpha & Beta` | `probe.Alpha` | `"probe.Alpha & probe.Beta"` |
| `Any` | `java.lang.Object` | `scala.Any` |
| `AnyRef` | `java.lang.Object` | `java.lang.Object` |

Two of those names are not loadable classes, and the intersection's is not a class name
at all. The same erasure applies in type-argument position — `List[String | Int]` has
the signature `List<java.lang.Object>` and `List[Alpha & Beta]` has `List<probe.Alpha>` —
so the mapping is safe wherever a type is modelled. A wildcard is excluded explicitly:
an unbounded one is `Nothing .. Any`, whose `typeSymbol` is `Any`'s, so without a guard
`List[?]` would stop being a wildcard. Pinned by `ScalaTypeErasureSpec`, including that
`A | Null` still means nullable-`A`.

**Value classes — fixed, and it needed the position.** A value class is
position-dependent, which the table did not capture. Measured with `javap` for
`class UserId(val value: String) extends AnyVal` and
`class Wrapped(val n: Int) extends AnyVal`:

| declared | JVM signature |
| --- | --- |
| `def ret(): UserId` | `java.lang.String ret()` |
| `def take(x: UserId): String` | `take(java.lang.String)` |
| `class Holder(val id: UserId, val count: Wrapped)` | field `java.lang.String`, field `int`; constructor `(Ljava/lang/String;I)V` |
| `def arr(): Array[UserId]` | `probe.UserId[] arr()` |
| `def list(): List[UserId]` | `java.util.List<probe.UserId> list()` |

So a value class is unboxed exactly at the top level of a signature — field, parameter,
return type — and stays boxed everywhere it is nested inside another type. Erasing
unconditionally is wrong in one position and not erasing is wrong in the other, so
`typeData` now carries a `TypePosition` as a contextual value: top level by default, and
nested inside a type argument or an array component. Nesting propagates, which is the
correct rule — everything below a nested position is nested too. A value class over a
primitive unboxes to that primitive (`Wrapped` becomes `int`, reported as primitive).
Pinned by `ScalaValueClassSpec`, one case per row of that table.

**Trait parameters — not a defect.** For `trait Base(val size: Int)` and
`class Child extends Base(3)`, `Child.getBeanProperties()` reports `size`. The parameter
is represented.

**Extension methods, `inline` — not a defect.** For a class declaring `plain`, an
`inline def inlined` and an `extension (s: String) def shout`, all three are visible
through `ElementQuery.ALL_METHODS.onlyDeclared()`. (Top-level extension methods, which
do live on a package's module class, are a different case and are not covered by this
check.)

**Fix.** For anything left: diagnose with a clear "unsupported for Scala" error rather
than silently producing a wrong model.

### B14 (MAJOR) Scala collection converters ignore the element type

`ScalaCollectionConverterRegistrar.java:47` and its siblings register
source/target class pairs but never read the `ConversionContext`, so no element is
converted to the target element type. Injecting `Seq[Duration]` or `List[Int]`
from a `@Value("${x}")` (a `List<String>`) yields a `Seq[String]` typed as
`Seq[Int]` — a `ClassCastException` at first use rather than a conversion error.
There is also no `scala.collection.Map -> java.util.Map` converter and no
converter into `scala.Option` from a non-`Optional` source, and Micronaut does
not chain converters.

**Fix — element conversion done.** Every converter now resolves the target element
type from the `ConversionContext` and converts each element through the conversion
service; maps convert keys and values, and `Option` converts its contained value.
An element that cannot be converted is rejected on the context and fails the
conversion, which is the difference between a binding error and a
`ClassCastException` later.

Reproduced before fixing: `List[Int]` bound from configuration held
`java.lang.String` elements, and `sum` threw
*"class java.lang.String cannot be cast to class java.lang.Integer"*. Pinned by
`ScalaCollectionElementConversionSpec`.

**Fix — reverse map and value-to-`Option` converters done.** A
`scala.collection.Map` is a `scala.collection.Iterable` of tuples, so the only
candidate converter was the one to `Collection`, which cannot produce a `Map`;
the conversion simply failed. It wraps when the target states no key or value
type -- the documented behaviour in this direction -- and copies when it has to
convert. And only `Optional -> scala.Option` was registered, so nothing could
build an `Option` from an ordinary value: a binder resolving
`timeout: Option[Int]` asked for an `Option` from the raw property value, got
nothing, and reported *"Property doesn't exist"* for a property that was set.
`Option` in a `@ConfigurationProperties` was unusable, and the diagnostic pointed
at the configuration rather than at the missing converter. Pinned by three cases
added to `ScalaCollectionElementConversionSpec`.

Still open, and **blocked on Core**: an `Option` over a property that is genuinely
absent. `TypeInformation.isOptional()` (`core/.../TypeInformation.java:213`) is
`type == Optional.class`, so `AbstractBeanResolutionContext.resolvePropertyValue`
treats an empty `scala.Option` argument as a missing property. Declaring the
parameter `@Nullable` is not a workaround: it substitutes a Java `null` for the
`None`, so the bean holds `null` where its own type says `Option`. Recorded in
**F2**.

### B15 (MINOR) Smaller items worth folding into the same passes

- **Owner half done; `isDefault()` is not a defect.** `getOwningType()` returning
  `declaringType` was the visible half of a larger problem and is fixed: inherited
  methods are now re-owned through `withNewOwningType` by the class the query was
  made on, as Core's Java module does. See the commit "Own an inherited method by
  the class it was reached through" for the `@ConfigurationProperties` prefix bug it
  was causing.

  `isDefault()` reporting every concrete trait method as a Java default method is
  **correct**, checked against emitted bytecode rather than assumed. Compiling
  `trait Greeter { def greet(): String = ... }` with dotty 3.9.0 and running `javap`
  gives `public default java.lang.String greet();` on the interface, alongside a
  static `greet$` forwarder. A concrete trait method really is a Java default
  method. (The static forwarder is generated in the backend, after these phases, so
  it is not visible here — the same reasoning as the varargs item in B13.)
- **Does not reproduce; left alone.** `getSyntheticBeanProperties()` does return all
  of `classData.properties()`, but that collection is *already* only the synthetic
  set: the plugin records a property for a `val`/`var` accessor, which the compiler
  generates, and not for hand-written accessors. Measured on a class with both --
  `class Holder(var fromConstructor: String)` plus a hand-written
  `getWritten`/`setWritten` pair -- `getSyntheticBeanProperties()` answers
  `[fromConstructor]` while `getBeanProperties()` answers both. Core walks the
  synthetic set for an ordinary bean, and an annotated hand-written setter produces
  exactly one injection point, not two. `ScalaPropertyData` indeed carries no
  synthetic flag; it does not need one while the collection is built this way, and
  the behaviour is now pinned by `ScalaSyntheticPropertySpec` so a change to how
  properties are collected cannot break it silently.
- **Done.** `ScalaEnumElement.elements()` constructed a fresh `ScalaEnumConstantElement`
  per call instead of going through the per-class cache every other element kind uses.
  Both halves reproduced: an annotation a visitor added to a constant it reached through
  `ElementQuery` was invisible through `elements()`, and two calls to `elements()`
  produced two different elements for the same constant. Pinned by
  `ScalaEnumConstantIdentitySpec`.
- **Does not reproduce; left alone.** `ScalaConstructorElement` does call
  `declaringType.getBeanProperties()` from inside a `computeIfAbsent`, but not on the
  same map: the five element caches are separate `IdentityHashMap`s, and the
  constructor's mapping function reaches `propertyElements`, never
  `constructorElements`. Instrumented all five caches with a re-entrancy counter across
  a full run of the suite: zero re-entries of any of them. Nesting a `computeIfAbsent`
  on one map inside another map's is defined behaviour. Worth re-checking if the
  caches are ever merged or if `getBeanProperties()` grows a path to the primary
  constructor.
- **Done.** `ScalaLoadedClassElement.getEnclosedElements` had no `PropertyElement` or
  `ClassElement` branch, so a property query and a nested-class query both came back
  empty for a classpath type while the source path answered both. It now mirrors the
  source branches, properties included in a `MemberElement` query unless the query
  excludes them. Pinned by `ScalaClasspathEnclosedElementSpec`.
- **Done, and it was two bugs.** `LoadedPropertyElement` overrode
  `getAnnotationMetadata()` but not `getAnnotationMetadataToWrite()`, so
  `annotate(...)` wrote to the element's own metadata while every read came from the
  property's. Fixing that exposed the second: annotating a classpath property threw
  *"Element of type [MethodElement$1] does not support adding annotations at
  compilation time"*. `MethodElement$1` is **not** a synthetic element from
  `MethodElement.of(...)`, as recorded earlier -- it is the anonymous delegate Core's
  *default* `getMethodAnnotationMetadata()` returns
  (`core-processor/.../MethodElement.java:61`), which overrides only the read side
  and inherits a throwing `annotate`. `ScalaMethodElement` overrides that method; the
  loaded method and constructor elements did not, so no classpath method could be
  annotated by anything. See the A6 note -- this is what was blocking its classpath
  half. Pinned by `ScalaClasspathEnclosedElementSpec`.
- **Reallocation done; package annotations are not worth carrying.** A package element
  was allocated afresh on every `getPackage()` call, so a package was as many elements
  as there were calls: an annotation added to one was invisible from the next call and
  from every other class in the same package. They are now created once per package
  name and shared, like every other element kind. Pinned by `ScalaPackageElementSpec`.

  The annotations half is left alone deliberately. Core's processor reads exactly one
  thing from a package element -- `isUnnamed()`, for the "beans cannot be in the
  default package" check in `AbstractBeanElementCreator.checkPackage` -- and nothing
  reads its annotation metadata. Scala 3 has no `package-info` construct either, so
  the only source would be a Java `package-info.class` on the classpath. Re-check if
  Core starts folding package metadata into the annotation hierarchy.
- **Half done; the other half is not a defect.** `isAssignable` ignored array
  dimensions. A `ClassElement` names its *component* type and reports dimensions
  separately, so an element for `Array[String]` is named `java.lang.String`, and
  comparing names made `Array[String]` assignable to `String`, `String` assignable
  to `Array[String]`, and `Array[Array[String]]` assignable to `Array[String]`.
  Micronaut matches bean types, `@Requires` conditions and executable handlers
  through this, so an array bean satisfied an injection point for its component
  type. Both implementations now compare dimensions, keep array assignment
  covariant in the component type, and answer true only for the three types every
  array really is (`Object`, `Cloneable`, `Serializable`). Pinned by
  `ScalaAssignabilitySpec`.

  Returning true for `java.lang.Object` unconditionally, primitives included, is
  **not** a defect: Core's own `PrimitiveElement.isAssignable(String)` does exactly
  the same (`core-processor/.../PrimitiveElement.java:78`). Left alone.
- **Done, but not as filed.** Overriding `getTypeArguments(String)` /
  `getAllTypeArguments()` is not the fix: Core's defaults compose correctly over
  `getSuperType()` / `getInterfaces()`, and the source element already answered
  right, including transitively (`Handler extends Base[String]`, `Base[E] extends
  Consumer[E]` resolves `Consumer.T` to `String`). The inputs were wrong.
  `ScalaLoadedClassElement` read its supertypes from `Class.getSuperclass()` and
  `Class.getInterfaces()`, which are erased, so `java.lang.String implements
  Comparable<String>` answered `T -> java.lang.Object`. Reading the generic
  signature is half of it; a supertype states its arguments in the subtype's own
  type variables (`ArrayList<E> extends AbstractList<E>`), so a binding also has to
  be substituted as the walk goes up or it survives one link. Pinned by
  `ScalaTypeArgumentLookupSpec`.
- **Done.** `isValidDefaultValue` (`ScalaAnnotationMetadataBuilder.java:542`) dropped
  empty-string defaults, which are extremely common (`@Named`, `@Property`,
  `@Requires.property`). An empty string is a real default, not the absence of one.
  Pinned by `ScalaAnnotationDefaultsSpec`.
- **Does not reproduce; left alone.** `annotationClassValue` / `enumValue` coerce with
  `String.valueOf` (`:480`, `:502`). Instrumented across the whole suite: the
  `annotationClassValue` fallback is never reached at all, and the `enumValue` fallback
  receives only `String` values that are already correct enum constant names
  (`RUNTIME`, `TYPE`, `AROUND`...), for which `String.valueOf` is a no-op. The
  `AnnotationClassValue("null")` the finding describes needs a null value, and none
  arrives -- null values are filtered before reaching either. Worth re-checking if a
  future change lets nulls through.
- **Does not reproduce; left alone.** `normalizeLooseValue` (`:438`) does not handle
  `ScalaAnnotationData`, `ScalaAnnotationData[]` or `ScalaClassValueData[]`.
  Instrumented across the whole suite: no raw Scala record ever reaches the
  fall-through. The "loose" path is only taken when a member has no resolved mirror,
  and A11 made mirrors resolve on demand, so that is now rare. Re-check alongside any
  change to mirror resolution.
- **Does not reproduce; left alone, but now pinned.** Positional arguments are indeed
  matched by index against `symbol.info.decls` order rather than against the primary
  constructor's parameter list, and the two agree: dotty lists constructor parameters
  *before* body members. Instrumented across a full run — the member lists reaching
  the matcher look like `List(alpha, beta, zeta, gamma)` for
  `class Marker(val alpha, val beta) { def zeta; val gamma }`, so index matching lands
  on the constructor parameters. Checked four shapes and all are correct: all-positional,
  positional mixed with named, an annotation with extra body members, and a *Java*
  annotation declaring another member before `value` (index matching alone would have
  chosen `other`; dotty resolves Java's single positional argument to `value` before the
  extractor sees it). Because the correctness rests on dotty's declaration order rather
  than on anything this code enforces, all four are now pinned by
  `ScalaPositionalAnnotationSpec`.

  The `value1`-inventing fallback is unreachable: instrumented across the whole suite,
  `legacyPositionalAnnotationMemberName` is called **zero** times. It runs only when the
  annotation type has no resolved mirror, and A11 made mirrors resolve on demand — the
  same reason the `normalizeLooseValue` item above does not reproduce. Re-check both
  alongside any change to mirror resolution.
- **Done, though it changes no answer.** `excludedHierarchyType` (`:653`) excluded only
  `Object` and `Enum`; it now also excludes `scala.Any`, `scala.AnyRef`, `scala.Product`,
  `scala.Equals`, `java.io.Serializable` and `scala.Serializable`. Measured before the
  change: over 250 hierarchy walks into those types in a single run of the suite. None of
  them can carry Micronaut metadata, so the metadata was already correct -- this removes
  the work, not a wrong result. `scala.annotation.Annotation` and `StaticAnnotation` are
  deliberately *not* excluded, being a real part of a Scala annotation class's hierarchy.
- **Found while fixing B11, now done:** `annotations(symbol)` returned the compiler's own
  `scala.annotation.internal.*` bookkeeping. dotty attaches `SourceFile` to every class it
  compiles, so that annotation was written into the metadata of every generated bean
  definition. Filtered out at extraction; pinned by `ScalaRetentionSpec`.
- **Done.** Cycle guards in `exceptionMessage` compared each cause only against the
  head of the chain, which catches a two-element cycle and nothing deeper: for
  `a -> b -> c -> b` the walk never returns to `a`. Both now follow the chain with a
  set of the exceptions already seen. Measured: with the guard reverted, the pinning
  spec ran for ten minutes without terminating -- the compiler hangs while producing a
  diagnostic, so the failure it was reporting is never seen. Pinned by
  `ScalaDiagnosticCycleSpec`, with a test visitor that fails with such a chain.
- **Half done; the other half is by design.** `optionsHelp` was `None` although
  options *are* parsed, so `-P:micronaut-scala:help` printed nothing and there was no
  discoverable record of which options mean anything. It now documents the syntax and
  the well-known `micronaut.processing.*` keys. Pinned by `ScalaPluginOptionsSpec`.

  Accepting a misspelled option as `key -> "true"` is **not** a defect to fix by
  validation: options go straight to `VisitorContext.getOptions()`, which Micronaut
  itself and any `TypeElementVisitor` on the compilation classpath may read, so the
  set is open by design -- the same contract as javac's `-A` options. There is no list
  to validate against. The help text says so.

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

### D6 (MAJOR) CI runs one Java version — the multi-JDK half is withdrawn

The multi-JDK part of this finding no longer applies: JDK 25 is the supported
baseline and earlier JDKs are explicitly out of scope, so a 17 or 21 matrix entry
is not wanted. The artifact/toolchain mismatch it noticed was real and is fixed —
the plugin targeted `-release:17` on its Scala sources only while the harness used
`-release:25`, and both now come from one constant.

The rest of this finding stands.

`verifyCompilerArtifacts` runs in no workflow (D4). And nothing tests the plugin
as an end user consumes it: there is no `doc-examples/`, no sample application,
and the `functional-test` convention plugin in `buildSrc` is applied by zero
projects. Every test drives `dotc` with `-Xplugin:<absolute path to the built jar>`,
which bypasses coordinate resolution and `-Xplugin:` fan-out — precisely the two
things D2 shows are broken.

**Fix.** Wire `verifyCompilerArtifacts` into CI (done); add a functional test that
resolves the published coordinate through a real `scalaCompilerPlugins`
configuration and compiles one `@Singleton` Scala class, plus an sbt equivalent.

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
- JDK 25 is the supported baseline: the plugin jar, the build and the tests all
  target it, and JDKs below 25 are explicitly out of scope.
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

### Wave 0 — make the build honest (prerequisite) — **done**

1. **Done.** `verifyCompilerArtifacts` is a dependency of `check`, the two dead
   assertions are replaced with an input-side check on what the jar task actually
   bundles, and the four missing assertions are in (D4). The service-descriptor
   assertion failed on the first run: `DuplicatesStrategy.EXCLUDE` had been
   dropping Core's `AnnotationConvertersRegistrar`,
   `ReactiveTypeConverterRegistrar` and `ReactiveStreamsTypeInformationProvider`
   from every plugin jar ever built, so the merge fix from D3 came forward into
   the same commit.
2. **Withdrawn; JDK 25 is the supported baseline.** Nothing below 25 is
   supported, so there is no 17 or 21 matrix entry to add -- and there could not
   be one anyway, since `micronaut-gradle-plugins` 8.0.1 refuses to run on
   anything below JVM 25. The finding did expose a real defect: `-release:17` was
   applied only to `ScalaCompile`, so the plugin jar was 23 classes at class file
   version 61 and 64 at version 69. Both halves now compile from one
   `javaTarget` constant, and `verifyCompilerArtifacts` asserts every entry is at
   exactly the expected version rather than merely under a ceiling, because the
   defect was divergence between the two compile tasks.
3. **Done, by refusing to publish.** Passing `-PincludeMicronautCore=true` to the
   publishing workflows would have made them succeed while uploading POMs
   declaring an unresolvable Core version, which is worse than the current
   failure. A task-graph check now fails any Sonatype or Maven Central
   publication while the Core version ends in `-SNAPSHOT`, naming the catalog
   entry to change. `publishToMavenLocal` is unaffected (D1).
4. **Done.** `AGENTS.md` is rewritten for the real repository; the
   `CONTRIBUTING.md` Docker and `githubCoreBranch` claims are corrected, as is
   the `TESTCONTAINERS_RYUK_DISABLED` env that expressed the Docker claim in CI;
   the README checklist and Examples residue, the `doc-examples` checkstyle
   suppression and `convention-base.gradle`'s micronaut-core spotless excludes
   and `--enable-preview` are gone. `config/accepted-api-changes.json` now
   exists, having been a declared japicmp input that did not. The unused
   `convention-geb-base.gradle` and its eight catalog aliases are deleted (D7).

Rationale: without this, later waves cannot tell a real regression from an
already-broken guard.

### Wave 1 — confirmed correctness defects — **done**

5. **Done** (`e55b469`). `hasAllFlags` and the four composite-flag call sites (A1).
6. **Done.** Both phases now override `runOn`, which the compiler calls exactly
   once per phase per run; the unit counter and the duplicated `processed`
   booleans are gone, and `processBeanDefinitions` starts by calling the
   idempotent `processTypeVisitors` so the ordering invariant lives in the engine
   (A2). A3 needs no work -- see its section above, the state was never shared
   across runs. Pinned by `ScalaPipelineOrderingSpec`.
7. **Done.** The reporter channel is `BiConsumer<String, Object>` carrying the
   native type, and the plugin resolves it to a `SrcPos` -- both `Positioned` and
   `Symbol` already extend it (A8). `ScalaCompiler.buildAndGetDiagnostics` exposes
   errors, which were previously observable only as a thrown exception. Pinned by
   `ScalaDiagnosticPositionSpec`.
8. **Done.** Annotation values are read from the typed tree: class literals from
   the `ClazzTag` constant on the tree's type, default arguments from the callee's
   symbol name, anything else reported as an error at the argument position (A9).
   Both `classOf`-substring scans and all three text-scanning helpers are gone.
   Pinned by `ScalaAnnotationValueSpec`.
9. **Done.** `withTypeArguments` (A4), `overrides`/`hides` (A5). Both copy paths on
   `ScalaClassElement` also stopped discarding the class's members. Pinned by
   `ScalaGenericsAndOverridesSpec`.
10. **Done.** The field-inheritance walk, which did not exist at all, now runs for
    supertypes in the compilation (A6), pinned by `ScalaInheritedFieldSpec`. The
    classpath half is done too, after A7, A18, this repository's own generic
    elements and the `getMethodAnnotationMetadata()` override — see A6 above, which
    also records the general rule the override stands for and the owning-type defect
    found while verifying it. Pinned by `ScalaClasspathInheritanceSpec`.

### Wave 2 — harness, then Scala-native tests

11. `compile(List<SourceFile>)`, `compilerOptions` on `buildBeanDefinition`/
    `buildContext`, the three-arg `buildBeanDefinition`, `buildIntroducedBeanDefinition`
    (C1). Stop swallowing load failures (C2). Clean up temp dirs and classloaders
    (C3, B4).
12. Write the P-1 Scala-native specs (C4/C5), starting with `object` beans,
    default arguments, varargs, by-name parameters and secondary constructors —
    these will fail, and the failures define the next fixes.
13. B11 (`object` as a bean) and A17 (field modifiers and reflection) are
    **done**, pinned by `ScalaObjectBeanSpec`. A15 (secondary constructors) and
    the by-name part of B13 landed earlier on this branch. Still open: the varargs
    and default-argument parts of B13, A14 (accessor collisions) and A16
    (qualified access).

### Wave 3 — annotation metadata unification — **fixes done; item 17 outstanding**

14. **Done.** Classpath elements build their metadata through
    `ScalaAnnotationMetadataBuilder` (A7), so stereotypes, aliases, mappers and
    repeatable containers resolve for them. Declared annotations only —
    annotations *inherited* from a classpath supertype wait on A18, and are
    recorded with A6's classpath half.
15. **Done, in two parts and not as proposed.** Defaults are collected across every
    unit of the compilation, and classpath defaults are read from the class file
    via `visitAnnotationDefault`, because dotty discards the value (A10).
16. **Done**, except `@Target` capture, which is still open. A11 resolves mirrors
    on demand; A12's retention default applies to Java annotation types only; A13
    unions getter and field annotations; of the B15 items, `isValidDefaultValue`
    is fixed and the two coercion items do not reproduce — see them for the
    evidence. Also fixed here, though not in the plan: the compiler's own
    `scala.annotation.internal.*` annotations were being written into every
    generated bean definition.
17. **Outstanding.** Port the P0 parity specs, which now encode fixed behaviour.
    This is bulk test work rather than adapter fixes, and is the largest remaining
    item in the plan.

### Wave 4 — element model consistency — **done, except item 21**

18. **Done.** Equality and copy semantics for placeholders, wildcards and loaded
    elements (B12), including the source/classpath identity unification;
    `getTypeArguments(String)`/`getAllTypeArguments()`, which turned out to be a
    classpath supertype problem rather than a missing override; and every B15 item.
    Six of the B15 items were measured and did not reproduce — each carries the
    evidence, and where the correct behaviour rested on something outside this code,
    a test pinning it.
19. **Done.** Classpath enumeration via the declared-member walk (A18), and then the
    classpath half of A6, which depended on it and on A7.
20. **Done.** Collection converters with element-type conversion, the reverse map
    converter and value-to-`Option` (B14).
21. **Outstanding.** Then port P1 and P2 parity specs.

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

## F2. Blocked on Micronaut Core

Items that cannot be closed in this repository. Each needs a change to Core
first; they are listed here so they are not mistaken for oversights.

### Scala default arguments at injection points (from B13)

A parameter with a default is treated as required, so a Scala bean whose
constructor or `@Executable` method relies on defaults cannot be satisfied the
way the source implies.

Core models optional parameters only through
`io.micronaut.inject.ast.KotlinParameterElement`, and the blocker is the
*generated code* rather than the marker interface:
`inject/writer/MethodGenUtils` builds an integer bitmask and calls Kotlin's
synthetic `$default` overload, which is Kotlin's calling convention. Scala emits
a separate zero-argument `<method>$default$<n>` getter per defaulted parameter
(`$lessinit$greater$default$<n>` for constructors), with no mask and no overload,
so implementing the Kotlin interface here would make Core generate calls to
methods that do not exist.

**The SPI now exists in Core, on a branch.** The investigation opened against
micronaut-core landed as `origin/claude/quizzical-panini-49fd3c`, and it is exactly
the shape this item asked for, in two parts:

- `ParameterElement.hasDefault()` reports only that a default *exists*. How the
  value is obtained is deliberately not part of that contract.
- `io.micronaut.inject.writer.ParameterDefaultValueProvider` is a service-loaded
  provider supplying the default as an `ExpressionDef` the **caller** evaluates at
  the invocation site. It is asked `supports(parameter)` in `Ordered` order and the
  first to return an expression wins.

The provider interface is written for exactly this case — its own javadoc uses Scala
as the motivating example, emitting
`new Greeter(greeting != null ? greeting : Greeter.$lessinit$greater$default$1())` —
and Core carries a `TestScalaLikeDefaultValueProvider` that is a working blueprint:
`supports` is an `instanceof` against the language's own parameter element, and
`defaultValueExpression` returns the accessor call.

**Still blocked, but only on merge order.** The work sits on an unmerged branch;
`checkouts/micronaut-core`, which this build compiles against, is at `367fe9d6a4`
and has neither `hasDefault()` nor the provider. Implementing against it now would
not compile here.

**Come back when:** that branch merges to `5.2.x`. The work here is then three
pieces, all small:

1. Recognise the `$default$` getters in the extractor and record per parameter
   whether one exists. Scala emits a zero-argument `<method>$default$<n>` per
   defaulted parameter, `$lessinit$greater$default$<n>` for constructors; the
   symbol-name detection added for annotation members in `defaultGetterReference`
   is the same mechanism.
2. Override `hasDefault()` on `ScalaParameterElement` from that.
3. Add a `ScalaParameterDefaultValueProvider` returning the accessor call as an
   `ExpressionDef`, registered in `META-INF/services`. Note the receiver: the
   constructor getters are static on the companion, so `target` is unused there,
   while a method's are instance methods on the declaring class.

Add specs for a constructor default and an `@Executable` method default, and move
this item back into a numbered wave.

### An absent property bound to a `scala.Option` (from B14)

`Option[T]` now binds from a property that is set -- that was a missing converter
and is fixed. A property that is *absent* still fails with *"Property doesn't
exist"* rather than yielding `None`.

`TypeInformation.isOptional()` (`core/src/main/java/io/micronaut/core/type/TypeInformation.java:213`)
is `type == Optional.class`, and `AbstractBeanResolutionContext.resolvePropertyValue`
branches on it: an argument that is not "optional" and has no value is a
missing-property error. Declaring the parameter `@Nullable` is not a workaround --
it takes the `isDeclaredNullable()` branch and substitutes a Java `null` for the
`None`, so the bean holds `null` where its own type says `Option`, which is worse
than the error.

**Blocked on:** Core recognising language-specific optional containers -- either
`isOptional()` consulting a registry of empty-value types (`scala.Option`,
`io.vavr.control.Option`, and so on), or a resolution hook that lets a converter
produce the empty value for an absent property.

**Come back when:** that changes. Nothing further is needed in this repository;
the converters are in place. Re-enable the `absent` case noted in
`ScalaCollectionElementConversionSpec`.

---

## G. Open questions to settle with a test

These were identified by review but need execution to confirm:

1. **Settled: yes, it works.** For `class Holder(var fromConstructor: String)` the
   extracted property reports `readOnly = false`, read method `fromConstructor` and
   write method `fromConstructor_$eq`, so `isPropertySetterDeclaration` does match
   and setter injection is not broken. Pinned by `ScalaSyntheticPropertySpec`.

   Found while settling it, and **not previously recorded**: a *hand-written* Scala
   property pair -- `def x: T` together with `def x_=(v: T): Unit` -- is not
   assembled into a `PropertyElement` at all. Both halves are visible as ordinary
   methods, so `@Inject` on the setter still works and injection is unaffected; what
   is missing is the property itself, so `@Introspected` on such a class does not
   expose `x`. `AstBeanPropertiesUtils` applies JavaBean conventions (`get`/`is`/`set`)
   and the plugin's own collection only covers `val`/`var`, so nothing covers Scala's
   own naming convention. Whether to add it is a design decision -- it changes what a
   bean exposes -- so it is recorded here rather than implemented.
2. Does `DirectoryClassWriterOutputVisitor.finish()` merge with a pre-existing
   service file, or truncate it? This decides how bad B10 is in practice.
3. What are the declared-vs-inherited semantics of the `TypeElementQuery` SPI that
   `ScalaProcessingEngine.java:287` uses `ElementQuery.ALL_FIELD_AND_METHODS` for?
   If declared-only is intended, it needs `.onlyDeclared()`.
4. Does `report.inform` print without `-verbose`? `VisitorContext.info(...)` is
   expected by Micronaut visitors to be user-visible.
