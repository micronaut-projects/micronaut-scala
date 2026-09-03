# Disabled Scala Tests

This file tracks parity source tests and Scala 3 adapter coverage status.
It was generated from the current checkout by scanning direct subclasses of Java `AbstractTypeElementSpec`, Groovy `AbstractBeanDefinitionSpec`, and Kotlin `AbstractKotlinCompilerSpec`.

Last refreshed: 2026-05-22.

The current grounding pass counted Java `194` specs / `1065` features,
Groovy `86` specs / `473` features, Kotlin `19` specs / `194` features, and
Scala `11` specs / `168` features. The detailed source inventory below keeps
the abstract/helper evaluated-expression base specs visible as candidate
comparison sources, so its raw source-spec count is one higher for Java and
Groovy than the executable corpus count.

## Current Scope

Wave 1 covers simple Scala classes, primary constructors, methods, constructor `val`/`var` properties, Java-visible annotations, basic type resolution, generated bean definitions, generated bean introspections, and a basic `TypeElementVisitor`.

Additional Wave 4 smoke coverage now exists for named qualifiers, `@Requires`,
constructor array injection, constructor `@Value` injection, field and method injection, post-construct and
pre-destroy lifecycle methods, `@InjectScope` dependencies, `BeanRegistration`
injection for constructor, field, method, collection, array, and named parameters,
simple `@Factory` methods, and executable methods, mutable
and immutable case-class `@ConfigurationProperties`, mixed configuration/bean
constructor injection, `@EachProperty`, and factory `val` property beans.
Nested `@ConfigurationProperties` are covered for Scala companion-object
nested classes. Source-defined default scopes, explicit scope overrides, factory
method overrides, and unscoped `@Bean` factory methods are covered for Scala
annotation stereotypes. Singleton Scala enum beans are rejected with the core
bean-definition error. Abstract bean collection filtering, abstract bean
definitions with injection points, qualifier-only beans, and AOP-only beans are
covered for Scala. Bean-definition type-string formatting and class-level
`@Bean(typed=...)` exposed type validation, including subclass rejection, are
partially covered for Scala. Factory method `@Bean(typed=...)` exposed type
validation is covered for valid and invalid factory return types. Bean
definition `@Order` metadata for top-level and companion-object nested beans,
bean definitions in packages with uppercase path segments, declared generic
bean type metadata on bean definitions and references, factory generic bean type
metadata, deep constructor generic argument metadata including nested type-use
validation annotation metadata, resolved type variables for generic bean
lookups including inherited array type arguments, and named, alias-driven, and
custom qualifier metadata are covered.
Required and optional `@Autowired` field and method injection are covered for
Scala. Dynamic `RuntimeBeanDefinition` registration from Scala source is
covered. Qualifier metadata on field-targeted Scala `var` injection is covered
for property setter injection. Evaluated expressions on Scala bean definitions
and executable methods are covered for Graal build-time initialization.
Focused P3 expression parity now covers constructor, method, factory, and
field-level `@Value` expression injection, `@Requires` env/property expression
values, and context-value `@Requires` expressions through a test
expression-context registrar. Field-targeted Scala `@Value` metadata is
propagated to the generated setter parameter while preserving optional
`@Inject(required = false)` value-injection behavior. Constructor-copy
introspection through an abstract Scala superclass is covered. Bean-introspection
constructor argument generics, generic array property/method return types, and
deep property type-use annotation metadata are covered; superclass introspection
constructor forwarding for byte arrays and boxed Boolean values, primitive and
class multi-dimensional array properties, companion-object nested class
introspection, subtype generic placeholders with upper bounds on bean properties
and nested Scala collection property type arguments, field-access introspection
for emitted Scala fields, and custom introspection target packages are covered.
Protobuf-style generic superclass shapes are covered for bean introspection.
Executable route methods inherited from source-defined Scala traits are covered.
Scala enum bean introspection can instantiate enum values through Scala's
emitted `valueOf(String)` method, expose enum constructor properties, and
expose enum constants through `EnumBeanIntrospection` by generating direct
Scala `valueOf(String)` calls into a Scala-only object-valued enum constant
reference instead of using runtime reflection or Java `Enum.valueOf(...)`.
Factory collection methods are covered for Java collection return types and
Scala immutable collection return types, including singleton Scala collection
factories producing element beans.
Scala collection injection is covered for constructor, method, and field
injection with common immutable and base collection types such as
idiomatic `List`, `scala.collection.immutable.List`, `scala.collection.immutable.Set`,
`scala.collection.Seq`, `scala.collection.IndexedSeq`, and
`scala.collection.immutable.Vector`, plus mutable `scala.collection.mutable.Buffer`
and `scala.collection.mutable.Seq`. Scala collection injection also covers
`BeanRegistration[T]` element semantics. String-keyed Scala map injection is
covered for constructor, method, and field injection with
`scala.collection.Map[String, T]`, `scala.collection.immutable.Map[String, T]`,
and `scala.collection.mutable.Map[String, T]`.
Scala `Option[T]` optional bean injection is covered for constructor, method,
and field injection, including empty options when no bean is present.
Scala collection configuration binding is covered for immutable list, mutable
buffer, mutable set, string-keyed immutable map, and string-keyed mutable map
constructor properties.

Recent Wave 3 coverage also includes class-body `val`/`var` properties,
generic type arguments, annotation arrays/class literals/enum constants,
repeatable annotations, field constant values, trait/interface assignability,
and Scala inner-class and companion-object nested-class enclosing type metadata,
and Scala enum declarations through `EnumElement`. Source-defined Scala annotation defaults,
stereotypes, and member aliases are now covered with compiler-symbol/typed-tree
metadata, including inherited source annotations through source class
hierarchies, classpath supertypes, bean definition processing, and
`TypeElementVisitor` annotation mutations, including mutation-added stereotypes
for source-defined Scala annotations. Source-defined Scala annotation classes
are exposed as `ClassElement`s, including Java `@Retention` and `@Target`
metadata recovered from compiler symbols on source-defined `StaticAnnotation`
classes.
Scala nullable and non-null annotations are covered for methods and parameters
through both `ClassElement` and bean introspection metadata.
Generic type argument nullability is covered for Java type-use annotations on
Scala generic type arguments.
Scala explicit-nulls union types such as `String | Null` are covered for
method returns, parameters, generic type arguments, constructor properties,
and introspection constructor argument and bean property metadata.
Scala generic type arguments are resolved through source-defined parent class
and trait hierarchies, including boxing primitive Scala value types used as
generic arguments. Inherited generic type arguments are also covered for
classpath Java collection hierarchies such as `List` to `Collection` to
`Iterable`. Class-level generic placeholders with upper bounds are exposed
through the Element API, and method-level generic placeholders are exposed
through `MethodElement`. Scala package element metadata is covered for the
default package and nested packages, including simple package names. Scala
primitive field types compare equal to shared `PrimitiveElement` constants.
Annotations on inherited Scala interface type arguments are propagated to
resolved inherited method parameter types.
Scala wildcard generic type arguments are covered for
unbounded, upper-bounded, and lower-bounded type arguments, including wildcard
bounds that reference Scala generic placeholders. Bean-definition generic
metadata resolves unbounded and upper-bounded wildcard arguments through Scala
type-parameter bounds.
Recursive Scala generic type parameter bounds such as `T <: Test[T]` are
covered for Element API traversal and terminate at `Object` after preserving a
bounded placeholder chain.
Scala object constants referenced from annotation values are resolved from
compiler symbol/type metadata without classloading.
`TypeElementVisitor` annotation mutations are covered for generated introduction
proxy methods, including inherited generic introduction methods with visitor-added
source-defined annotation metadata. Focused P0 mutation parity now also covers
visitor-added annotations on Scala classes, methods, fields, field types,
properties, and parameters, plus visitor-added repeatables, empty arrays, and
source-defined stereotypes.
Visitor-added annotations on Scala return and parameter type `ClassElement`
wrappers and generic type arguments are covered by preserving Scala wrapper
`ClassElement` instances across repeated type and type-argument access.
`TypeElementQuery` field, method, and constructor inclusion is covered for
Scala type visitors. Scala `ElementQuery` filtering is covered for inherited
methods, including abstract/concrete and accessible method selection across
source-defined class and trait hierarchies. Scala emitted field query semantics
are covered for all/private/accessible field selection, with emitted fields
modelled as private and reflection-required.
Scala `@throws[...]` method declarations are covered through
`MethodElement.getThrownTypes()`.
Empty array annotation members are covered on generated executable methods.
Focused P1 parity now covers Scala numbered property names, overloaded
`@Executable` introspection methods, `BeanProvider` and
`jakarta.inject.Provider` injection, `@Replaces`, abstract parent constructor
injection, factory `val`/method beans, enum-returning factories, null-return
and disabled each-bean factory methods, primitive and raw-map configuration
binding, and cascaded validation on nested configuration properties, plus
factory-backed configuration property binding, repeatable Java qualifier
annotations on Scala injection points and factory methods, and source-defined
Scala qualifier members marked `@NonBinding`, implicit `@Named` qualifier
inference, plus overloaded factory methods that would otherwise collide on
generated bean-definition names. Scala introspection include/exclude rules are
covered for constructor properties.
Scala covariant JavaBean-style properties are covered through shared
bean-property resolution. External-class introspection from Scala
`@Introspected(classes = ...)` is covered using Micronaut's long external
introspection name.
Source-defined Scala `@Around(proxyTarget = true)` advice is covered for
generated proxy-target beans, interceptor invocation, and target lifecycle.
Source-defined Scala `@Around(proxyTarget = true, hotswap = true)` advice is
covered for generated hot-swappable proxy-target beans and target swapping.
Scala introduction proxy method metadata resolves inherited generic return
types through source-defined trait type arguments, including nested generics,
method type variables, and arrays.
Focused P2 parity now covers final-method AOP diagnostics, inherited
`@PostConstruct`/`@PreDestroy` lifecycle hooks, `@Bean(preDestroy = ...)`
factory method hooks, inherited overloaded `@Executable` trait methods, and
Scala `@Adapter` methods backed by classpath Java SAM interfaces. Around
advice on inherited Scala trait default methods is covered by modelling
concrete Scala trait methods as JVM default interface methods and by making
generated around-proxy bridge classes directly implement the interface they
invoke with `invokespecial`. Introduction combined with around advice and
additional interfaces is covered by rebinding source Scala method owning types
while preserving their original declaring traits, matching the Java, Groovy,
Kotlin, and loaded-Scala method element contract.
`ScalaBeanElementBuilderParitySpec` covers visitor-created associated beans,
associated factory beans, multiple generated factories with qualifiers and
injected parameters, generated executable method metadata, and AOP on generated
beans.
Bean import is documented as unsupported for Scala.

## Classification Rules

- `covered`: current Scala coverage already exercises the core scenario, although broader parity can still be added later.
- `candidate`: useful parity coverage that should be ported or split into a focused Scala parity spec.
- `unsupported`: useful to document as unsupported for Scala rather than silently dropping from the backlog.
- `scala-specific`: Java, Groovy, or Kotlin syntax/compiler behavior that should be skipped or replaced with Scala-native coverage.

## Inventory Summary

| Source | covered | candidate | unsupported | scala-specific | detailed total |
| --- | ---: | ---: | ---: | ---: | ---: |
| Java AbstractTypeElementSpec | 24 | 159 | 1 | 11 | 195 |
| Groovy AbstractBeanDefinitionSpec | 6 | 70 | 0 | 11 | 87 |
| Kotlin AbstractKotlinCompilerSpec | 2 | 14 | 0 | 3 | 19 |
| Total | 32 | 243 | 1 | 25 | 301 |

## Priority Buckets

### P0: Catalog, Element API, and Annotation Parity

- Keep this file current as Scala parity coverage changes.
- Add `ScalaReconstructionSpec`: field/method/parameter/return reconstruction,
  arrays, wildcards, type variables on classes and methods, inherited type
  arguments, traits/interfaces, enums, and inner/nested classes.
- Add `ScalaVisitorContextSpec`: `getClassElement`, `getClassElements`, enum
  lookup, nested-class lookup, missing-class behavior, and no classloading
  assumptions.
- Add `ScalaElementMutationParitySpec`: visitor-added annotations on class,
  method, field, property, parameter, return type, field type, and type
  arguments; repeatables; empty arrays; and stereotypes.
- Add `ScalaAnnotationMetadataParitySpec`: annotation defaults, nested
  annotations, class literals, enum constants, arrays, retention/target
  filtering, source-defined stereotypes, aliasing, and `ProcessingException`
  messages with originating elements.
- Add `ScalaAnnotationMappingParitySpec`: typed annotation mapper,
  transformer, and package remapper behavior on Scala class annotations,
  including mapper retention and transformer/remapper removal. Repeatable
  outputs from mapping remain a comparison candidate.

### P1: Introspection, Bean Definition, and Configuration Parity

- Extend `ScalaBeanIntrospectionSpec`: property include/exclude/access-kind
  rules, covariant properties, numbered property names, creator selection,
  executable methods, validation metadata, generic placeholders, enum creator
  behavior, interface/trait inheritance, and external-class introspection where
  Scala can model it.
- Extend `ScalaBeanDefinitionSpec`: unresolved-type diagnostics, provider and
  `BeanProvider` injection, optional property injection,
  repeatable/non-binding qualifiers, `@Replaces`, inherited qualifier negative
  cases, abstract parent injection, factory field/val/method beans, generic
  factories, enum-returning factories, null-return factories, and factory
  method name collisions.
- Extend configuration coverage: interface/trait config props, nested config
  props, validation cascades, inherited prefixes/aliases, raw maps, primitives,
  `@EachProperty` nesting/replacement, and factory-backed config props.

### P2: AOP, Lifecycle, and Executable Parity

- Add `ScalaAopParitySpec`: around construct, around advice on inherited
  trait/default methods, introduction with around, mapped introduction,
  additional interfaces, abstract class/trait introduction, final-method
  errors, named AOP target lookup, adapter methods, and factory-level advice.
- Extend lifecycle coverage: inherited `@PostConstruct`/`@PreDestroy`, hooks on
  `@Bean` factory members, hooks with AOP/proxy-target, and private/protected
  hook behavior where Scala can express it.
- Extend executable coverage: inherited executable trait methods, overloaded
  methods, generics, annotation metadata inheritance, and executable factory
  methods.

### P3: Visitor-Generated Beans and Build-Time Behavior

- Add `ScalaBeanElementBuilderParitySpec`: visitor-created beans, associated
  factory beans, multiple generated factories, generated methods, and AOP on
  generated beans. These cases are covered through Scala originating-element
  associated bean support and the shared bean element builder writer path.
- Add visitor-order and postponed-visitor coverage only where Scala's
  compiler-plugin phase model can reproduce the Java/Groovy/Kotlin intent.
  Scala visitor ordering is covered by `ScalaVisitorOrderingSpec`; Java
  annotation-processing postponed rounds are Scala-specific and are not ported
  where they depend on generated types appearing in later annotation-processing
  rounds.
- Add evaluated-expression parity for `@Requires` expressions,
  context/property/environment expressions, expression injection, and
  annotation-level expressions. Constructor, method, and factory expression
  injection, field expression injection, and `@Requires` env/property/context
  expressions are now covered.

## First Candidate Ports

Start with small tests that exercise already-supported Scala forms before the broader priority buckets. Suggested source comparison specs:

- `inject-java-test/src/test/groovy/io/micronaut/inject/visitor/beans/BeanIntrospectionSpec.groovy`
- `inject-java/src/test/groovy/io/micronaut/inject/beans/BeanDefinitionSpec.groovy`
- `inject-groovy-test/src/main/groovy/io/micronaut/ast/transform/test/AbstractEvaluatedExpressionsSpec.groovy`
- `inject-groovy/src/test/groovy/io/micronaut/inject/beans/BeanDefinitionSpec.groovy`
- `inject-groovy/src/test/groovy/io/micronaut/inject/visitor/BeanIntrospectionSpec.groovy`
- `inject-groovy/src/test/groovy/io/micronaut/inject/visitor/ClassElementSpec.groovy`

## Detailed Inventory

### Java AbstractTypeElementSpec

- `inject-java-test/src/main/groovy/io/micronaut/annotation/processing/test/AbstractEvaluatedExpressionsSpec.groovy` - candidate: partially covered by focused Scala expression injection and `@Requires` expression parity; direct generated-expression class evaluation and expression type collection remain comparison candidates
- `inject-java-test/src/test/groovy/io/micronaut/inject/annotation/AnnotationMetadataBuilderSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java-test/src/test/groovy/io/micronaut/inject/annotation/AnnotationTransformerSpec.groovy` - candidate: partially covered by `ScalaAnnotationMappingParitySpec` for transformer removal; retention-level transformer behavior remains a comparison candidate
- `inject-java-test/src/test/groovy/io/micronaut/inject/annotation/InheritedNullableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java-test/src/test/groovy/io/micronaut/inject/beanimport/BeanImportSpec.groovy` - unsupported: bean import is not implemented for Scala and should be documented as unsupported in a future docs pass
- `inject-java-test/src/test/groovy/io/micronaut/inject/visitor/ElementAnnotateSpec.groovy` - covered: Scala `TypeElementVisitor` annotation mutations are covered for classes, methods, parameters, introspection properties, source-defined stereotypes, and introduction proxy methods
- `inject-java-test/src/test/groovy/io/micronaut/inject/visitor/InheritanceVisitorSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java-test/src/test/groovy/io/micronaut/inject/visitor/beans/AnnotatedIntrospectedSpec.groovy` - covered: basic @Introspected class metadata is covered by ScalaPoCSpec
- `inject-java-test/src/test/groovy/io/micronaut/inject/visitor/beans/BeanIntrospectionGenericsSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java-test/src/test/groovy/io/micronaut/inject/visitor/beans/BeanIntrospectionSpec.groovy` - candidate: partially covered for constructor argument generics, generic array property/method types, multi-dimensional array properties, deep property type-use annotation metadata, superclass constructor forwarding, companion-object nested class introspection, subtype generic placeholders with upper bounds, protobuf-style generic superclass shapes, field-access introspection for emitted Scala fields, covariant JavaBean-style properties, external-class introspection from an introspection target, and custom introspection target packages; remaining introspection, creator, generics, metadata, enum, and interface cases should be ported incrementally
- `inject-java-test/src/test/groovy/io/micronaut/inject/visitor/beans/BuildClassElementSpec.groovy` - covered: basic buildClassElement coverage is present in ScalaPoCSpec
- `inject-java/src/test/groovy/io/micronaut/annotation/AnnotateClassSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/AnnotateFieldSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/AnnotateFieldTypeSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/AnnotateMethodParameterSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/AnnotateMethodReturnSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/AnnotateMethodSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/AnnotatePropertySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/AnnotateTypeArgSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/JavaEnumElementSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-java/src/test/groovy/io/micronaut/annotation/NonNullabilityAnnotationsSpec.groovy` - covered: Scala non-null annotations on methods and parameters are covered through `ClassElement` and bean introspection metadata
- `inject-java/src/test/groovy/io/micronaut/annotation/NullabilityAnnotationsSpec.groovy` - covered: Scala nullable annotations on methods and parameters are covered through `ClassElement` and bean introspection metadata
- `inject-java/src/test/groovy/io/micronaut/annotation/NullabilityFutureAnnotationsSpec.groovy` - covered: Scala generic type argument nullability is covered for JSpecify type-use annotations
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/AddsRepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/AddsUnseenInnerRepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/AddsUnseenRepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/AnnotationMappingSpec.groovy` - candidate: partially covered by `ScalaAnnotationMappingParitySpec` for typed mapper execution and mapper-retained source annotations; domain-specific stereotype mapping remains a comparison candidate
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/MapToRepeatableAnnotationSpec.groovy` - candidate: partially covered by `ScalaAnnotationMappingParitySpec` for typed mapper execution; repeatable mapped outputs remain a comparison candidate
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/MappedValueHasDefaultSpec.groovy` - candidate: partially covered by `ScalaAnnotationMappingParitySpec` for explicit Scala annotation member values; Java annotation default member visibility to mappers remains a comparison candidate
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/RemapToRepeatableAnnotationSpec.groovy` - candidate: partially covered by `ScalaAnnotationMappingParitySpec` for package remapper removal; repeatable remapped outputs remain a comparison candidate
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/ReplacesRepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/SourceAnnotationHasDefaultsSpec.groovy` - candidate: Scala source-defined annotation defaults are covered by `ScalaAnnotationMetadataParitySpec`; Java annotation default member visibility to Scala mappers remains a comparison candidate
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/TransformNotInheritedAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/TransformToInheritedAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/annotation/mapping/TransformsToRepeatableAnnotationSpec.groovy` - candidate: partially covered by `ScalaAnnotationMappingParitySpec` for transformer removal; repeatable transformed outputs remain a comparison candidate
- `inject-java/src/test/groovy/io/micronaut/annotation/processing/visitor/JavaReconstructionSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-java/src/test/groovy/io/micronaut/annotation/processing/visitor/JavaVisitorContextSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-java/src/test/groovy/io/micronaut/annotation/processing/visitor/JavaVisitorSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-java/src/test/groovy/io/micronaut/aop/adapter/MethodAdapterSpec.groovy` - candidate: partially covered by Scala `@Adapter` methods for classpath Java SAM interfaces; broader adapter overload, error, and intercepted-adapter variants remain comparison candidates
- `inject-java/src/test/groovy/io/micronaut/aop/adapter/intercepted/InterceptedAdapterSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/AbstractClassIntroductionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/AnnotatedConstructorArgumentSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/AroundCompileSpec.groovy` - candidate: partially covered for around advice on inherited Scala trait default methods; broader around-advice scenarios remain comparison candidates
- `inject-java/src/test/groovy/io/micronaut/aop/compile/AroundConstructCompileSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/ExecutableFactoryMethodSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/FinalModifierSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/GeneratedAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/InheritedAnnotationMetadataSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/InjectFieldAbstractIntroductionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/IntroductionAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/IntroductionCompileSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/IntroductionGenericTypesSpec.groovy` - covered: Scala introduction proxy method metadata resolves inherited generic return types through source-defined trait type arguments, including nested generics, method type variables, and arrays
- `inject-java/src/test/groovy/io/micronaut/aop/compile/IntroductionInnerInterfaceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/IntroductionWithAroundSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/LifeCycleWithProxySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/LifeCycleWithProxyTargetSpec.groovy` - covered: Scala source-defined `@Around(proxyTarget = true)` advice is covered for generated proxy-target beans, interceptor invocation, and target lifecycle
- `inject-java/src/test/groovy/io/micronaut/aop/compile/OriginatingElementsSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/PostConstructInterceptorCompileSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/compile/ValidatedNonBeanSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/factory/AdviceDefinedOnFactorySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/factory/SessionProxySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/factory/mapped/FactoryMappedAdviceReflectionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/factory/mapped/FactoryMappedAdviceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/introduction/InterfaceIntroductionAdviceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/introduction/IntroductionAdviceWithNewInterfaceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/introduction/MappedIntroductionOnConcreteClassSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/introduction/MyAbstractRepoSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/introduction/beans/IntroducedBeanVisitorSpec.groovy` - covered: Scala inherited introduction methods with generic return types, generic publisher parameters, resolved generic parameters, and `@InterceptorBean` bindings are covered by `ScalaIntroducedBeanVisitorSpec`
- `inject-java/src/test/groovy/io/micronaut/aop/introduction/repeatable/IntroducedWithRepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/introduction/with_around/IntroductionInnerInterfaceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/aop/introduction/with_around/IntroductionWithAroundOnConcreteClassSpec.groovy` - candidate: partially covered for Scala introduction combined with around advice and additional interfaces; broader concrete-class scenarios remain comparison candidates
- `inject-java/src/test/groovy/io/micronaut/aop/named2/NamedAopAdviceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/core/io/service/ServiceLoaderFeatureSpec.groovy` - covered: Scala evaluated expressions on bean definitions and executable methods are registered for Graal build-time initialization
- `inject-java/src/test/groovy/io/micronaut/inject/aliasfor/AliasForQualifierSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/AddStereotypesFromVisitorSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/AnnotatedFieldWithSetterSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/AnnotationDefaultValuesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/AnnotationInheritanceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/AnnotationMapperSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/AnnotationMetadataHierarchySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/AnnotationMetadataWriterSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/AnnotationRemapperSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/AnnotationsOnGenericTypesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/ArgumentAnnotationMetadataSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/BeanDefinitionAnnotationMetadataSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/JavaAnnotationMetadataBuilderSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/JavaxMapperSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/PriorityAnnotationMapperSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/RemoveAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/RetentionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/repeatable/MapToRepeatableSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/repeatable/RepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/annotation/repeatable/TransformToRepeatableSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/ast/beans/BeanElementVisitorSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/autowired/AutowiredSpec.groovy` - covered: Scala field and method `@Autowired` injection is covered for required and optional dependencies, including optional value injection and multi-argument method skipping
- `inject-java/src/test/groovy/io/micronaut/inject/beanbuilder/BeanElementBuilderFactorySpec.groovy` - covered: Scala associated factory beans are covered by `ScalaBeanElementBuilderParitySpec`
- `inject-java/src/test/groovy/io/micronaut/inject/beanbuilder/BeanElementBuilderMultipleFactorySpec.groovy` - covered: Scala multiple generated factories with qualifiers and injected factory parameters are covered by `ScalaBeanElementBuilderParitySpec`
- `inject-java/src/test/groovy/io/micronaut/inject/beanbuilder/BeanElementBuilderSpec.groovy` - candidate: broader visitor-created bean, static creator, interceptor adapter, and type-argument coverage remains beyond the associated bean, generated factory, executable method, and generated AOP cases now covered
- `inject-java/src/test/groovy/io/micronaut/inject/beanbuilder/BuildElementBuilderAopOnMethodSpec.groovy` - covered: Scala method-level AOP on visitor-generated beans is covered by `ScalaBeanElementBuilderParitySpec`
- `inject-java/src/test/groovy/io/micronaut/inject/beanbuilder/BuildElementBuilderAopOnTypeSpec.groovy` - covered: Scala type-level AOP on visitor-generated beans is covered by `ScalaBeanElementBuilderParitySpec`
- `inject-java/src/test/groovy/io/micronaut/inject/beanbuilder/BuildElementBuilderProcessedMethodsSpec.groovy` - covered: Scala executable method metadata on visitor-generated beans is covered by `ScalaBeanElementBuilderParitySpec`
- `inject-java/src/test/groovy/io/micronaut/inject/beans/AbstractBeanSpec.groovy` - covered: Scala source-level abstract bean scenarios are covered for collection filtering, abstract definitions with injection points, qualifier-only beans, and AOP-only beans
- `inject-java/src/test/groovy/io/micronaut/inject/beans/BeanDefinitionSpec.groovy` - candidate: partially covered for type-string formatting, class/factory-level `@Bean(typed=...)` exposed type validation including subclass rejection, `@Order` metadata, uppercase package names, declared generic bean type metadata on definitions and references, factory generic bean type metadata, deep constructor generic argument and annotation metadata, resolved type-variable generic lookups including inherited array type arguments, unbounded and upper-bounded wildcard generic bounds, and qualifier metadata; remaining additional type-variable cases should be ported incrementally
- `inject-java/src/test/groovy/io/micronaut/inject/beans/BeanRegistrationSpec.groovy` - covered: Scala constructor, field, method, collection, array, and named `BeanRegistration` injection are covered
- `inject-java/src/test/groovy/io/micronaut/inject/beans/RuntimeBeanDefinitionSpec.groovy` - covered: Scala source-level dynamic bean definition registration is covered; the remaining runtime builder assertions are not language-adapter-specific
- `inject-java/src/test/groovy/io/micronaut/inject/beans/concopy/ConstructorCopySpec.groovy` - covered: Scala introspection handles constructor forwarding through an abstract superclass
- `inject-java/src/test/groovy/io/micronaut/inject/beans/visitor/MapperVisitorSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/ConfigPropertiesParseSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/ConfigurationPropertiesBuilderSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/ConfigurationPropertiesInjectSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/ImmutableConfigurationPropertiesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/InheritedConfigurationReaderPrefixSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/InterfaceConfigurationPropertiesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/ValidatedConfigurationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/VisibilityIssuesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/eachbeaninterceptor/EachBeanInterceptorSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/eachbeanparameter/EachBeanParameterSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/eachbeanreplaces/EachBeanReplacesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/itfce/InterfaceNestingSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/nesting/EachPropertyNestingSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configproperties/records/RecordNestingSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-java/src/test/groovy/io/micronaut/inject/configuration/ConfigurationBuilderSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configuration/ConfigurationBuilderSpec2.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configuration/ConfigurationJsonSchemaDefaultsSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configuration/ConfigurationJsonSchemaSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configuration/ConfigurationJsonSchemaValidationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configuration/ConfigurationMetadataSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configuration/ExternalConfigurationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/configurations/RequiresBeanCompileSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/constructor/arrayinjection/ConstructorArrayInjectionSpec.groovy` - covered: Scala array constructor injection is covered for bean definition parsing and runtime injection
- `inject-java/src/test/groovy/io/micronaut/inject/context/NoPackageSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-java/src/test/groovy/io/micronaut/inject/defaultimpl/DefaultImplementationSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-java/src/test/groovy/io/micronaut/inject/errors/SingletonOnEnumSpec.groovy` - covered: singleton Scala enum beans are rejected with the core bean-definition error
- `inject-java/src/test/groovy/io/micronaut/inject/executable/ExecutableBeanSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/executable/ExecutableSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/executable/inheritance/InheritedExecutableSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/factory/ExecutableAnnotationOnFactorySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/factory/FactoryBeanDefinitionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/factory/FactoryOfBeanWithUnresolvedClassSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/factory/beanfield/FactoryBeanFieldSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/factory/beanfield/FactoryFieldArraySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/factory/beanmethod/FactoryBeanMethodSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/factory/collection/FactoryArraySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/factory/collection/FactoryCollectionSpec.groovy` - pending: Scala `@Bean` factory methods returning Java or Scala collections generate references but are not returned as element-bean candidates; tracked by the pending collection-factory tests in `ScalaBeanDefinitionSpec`
- `inject-java/src/test/groovy/io/micronaut/inject/factory/enummethod/FactoryEnumSpec.groovy` - covered: Scala factory methods can return Scala enum beans
- `inject-java/src/test/groovy/io/micronaut/inject/factory/generics/GenericFactorySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/factory/inheritance/FactoryAbstractInheritanceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/factory/lifecycle/PreDestroyOnBeanAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/factory/multiple/MethodSameNameSpec.groovy` - covered: overloaded Scala factory methods generate distinct bean-definition references
- `inject-java/src/test/groovy/io/micronaut/inject/factory/named/ImplicitNamedSpec.groovy` - covered: Scala implicit `@Named` qualifiers are inferred on bean types, factory methods, and constructor injection points
- `inject-java/src/test/groovy/io/micronaut/inject/factory/nullreturn/NullReturnFactorySpec.groovy` - covered: Scala factories cover non-null factory methods that return `null`, disabled `@EachBean` products, nullable and `@Parameter` each-bean arguments, and missing dependencies from disabled factory beans
- `inject-java/src/test/groovy/io/micronaut/inject/factory/proxytarget/FactoryWithScopedProxySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/field/inheritance/FieldInheritanceInjectionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/field/simpleinjection/FieldInjectionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/foreach/EachPropertyParseSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/generics/GenericTypeArgumentsSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/generics/TypeArgumentsSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/inheritance/AbstractInheritanceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/injectscope/InjectScopeSpec.groovy` - covered: Scala constructor and method parameters support `@InjectScope` scoped dependency cleanup
- `inject-java/src/test/groovy/io/micronaut/inject/lifecycle/PostConstructCompileSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/lifecycle/PreDestroyCompileSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/lifecycle/beanwithpostconstruct/BeanWithPostConstructSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/lifecycle/beanwithprivatepostconstruct/BeanWithPostConstructSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/method/arrayinjection/SetterArrayInjectionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/method/builderinjection/BuilderStyleInjectionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/method/qualifierinjection/SetterWithQualifierSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/optional/OptionalPropertySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/property/PropertyAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/provider/BeanProviderSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/provider/DisableErrorOnMissingBeanProviderSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/qualifiers/annotation/AnnotationQualifierSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/qualifiers/annotationmember/NonBindingQualifierSpec.groovy` - covered: Scala source-defined qualifier annotations support getter-targeted `@NonBinding` members for bean resolution and qualifier metadata
- `inject-java/src/test/groovy/io/micronaut/inject/qualifiers/named/NamedQualifierSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/qualifiers/repeatable/RepeatableQualifierSpec.groovy` - covered: classpath Java repeatable qualifiers are resolved on Scala constructor injection points, factory methods, and `BeanRegistration` injection
- `inject-java/src/test/groovy/io/micronaut/inject/qualifiers/replaces/AnnotateReplacesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/qualifiers/replaces/ReplacesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/records/RecordBeansSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-java/src/test/groovy/io/micronaut/inject/requires/RequiresBeanPropertiesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/requires/RequiresSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/inject/scope/DefaultScopeSpec.groovy` - covered: source-defined default scope, explicit scope override, factory method override, and unscoped `@Bean` factory method variants are covered
- `inject-java/src/test/groovy/io/micronaut/inject/value/factorywithvalue/FactoryWithValueSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/visitors/ClassElementAnnotationsRetaining.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/visitors/ClassElementSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/visitors/CustomVisitorSpec.groovy` - covered: basic TypeElementVisitor class/method/property observation is covered by ScalaPoCSpec
- `inject-java/src/test/groovy/io/micronaut/visitors/DocumentationSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-java/src/test/groovy/io/micronaut/visitors/ImportTypeElementSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/visitors/InternalVisitor1Spec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/visitors/InternalVisitor2Spec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/visitors/InternalVisitor3Spec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/visitors/IntroductionVisitorSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/visitors/MixinSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/visitors/NullableElementSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/visitors/PostponedVisitorsSpec.groovy` - scala-specific: Java annotation-processing round postponement does not map directly to Scala compiler-plugin phases; Scala visitor ordering is covered by `ScalaVisitorOrderingSpec`, while generated-bean behavior remains tracked by the pending bean builder parity tests
- `inject-java/src/test/groovy/io/micronaut/visitors/PropertyElementSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-java/src/test/groovy/io/micronaut/visitors/query/TypeElementQuerySpec.groovy` - covered: Scala type visitors cover `TypeElementQuery` field, method, and constructor inclusion

### Groovy AbstractBeanDefinitionSpec

- `inject-groovy-test/src/main/groovy/io/micronaut/ast/transform/test/AbstractEvaluatedExpressionsSpec.groovy` - candidate: partially covered by focused Scala expression injection and `@Requires` expression parity; direct generated-expression class evaluation and expression type collection remain comparison candidates
- `inject-groovy/src/test/groovy/io/micronaut/aop/adapter/MethodAdapterSpec.groovy` - candidate: partially covered by Scala `@Adapter` methods for classpath Java SAM interfaces; broader adapter overload, error, and intercepted-adapter variants remain comparison candidates
- `inject-groovy/src/test/groovy/io/micronaut/aop/compile/AbstractClassIntroductionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/compile/AroundCompileSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/compile/ExecutableDefaultMethodSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/compile/ExecutableSuperclassSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/compile/FinalModifierSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/compile/InheritedAnnotationMetadataSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/compile/IntroductionGenericTypesSpec.groovy` - covered: Scala introduction proxy method metadata resolves inherited generic return types through source-defined trait type arguments, including nested generics, method type variables, and arrays
- `inject-groovy/src/test/groovy/io/micronaut/aop/compile/IntroductionWithAroundSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/compile/LifeCycleWithProxySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/compile/PropertyAdviceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/compile/ValidatedNonBeanSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/factory/SessionProxySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/introduction/InterfaceIntroductionAdviceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/introduction/IntroductionAdviceWithNewInterfaceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/introduction/with_around/IntroductionInnerInterfaceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/aop/introduction/with_around/IntroductionWithAroundOnConcreteClassSpec.groovy` - candidate: partially covered for Scala introduction combined with around advice and additional interfaces; broader concrete-class scenarios remain comparison candidates
- `inject-groovy/src/test/groovy/io/micronaut/ast/groovy/annotation/GroovyAnnotationMetadataBuilderSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-groovy/src/test/groovy/io/micronaut/ast/groovy/visitor/GroovyBeanPropertiesSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-groovy/src/test/groovy/io/micronaut/ast/groovy/visitor/GroovyDocumentationSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-groovy/src/test/groovy/io/micronaut/ast/groovy/visitor/GroovyEnclosedElementsSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-groovy/src/test/groovy/io/micronaut/ast/groovy/visitor/GroovyEnumElementSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-groovy/src/test/groovy/io/micronaut/ast/groovy/visitor/GroovyReconstructionSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-groovy/src/test/groovy/io/micronaut/expressions/TestExpressionsInjectionSpec.groovy` - candidate: partially covered by Scala constructor, method, factory, and field `@Value` expression injection; broader expression shapes such as static calls and factory context-value injection remain comparison candidates
- `inject-groovy/src/test/groovy/io/micronaut/expressions/TestExpressionsUsageSpec.groovy` - covered: Scala covers evaluated `@Requires` env/property values, context-value expressions, and disabled bean behavior
- `inject-groovy/src/test/groovy/io/micronaut/inject/aliasfor/AliasForQualifierSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/AnnotationMetadataWriterSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/GroovyAnnotationInheritanceSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/GroovyMappedStereotypesSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/RemoveAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/RetentionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/modify/AnnotateFieldSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/modify/AnnotateFieldTypeSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/modify/AnnotateMethodParameterSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/modify/AnnotateMethodReturnSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/modify/AnnotateMethodSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/modify/AnnotatePropertySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/repeatable/AddsRepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/repeatable/RepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/annotation/repeatable/ReplacesRepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/beanbuilder/BeanElementBuilderFactorySpec.groovy` - covered: Scala associated factory beans are covered by `ScalaBeanElementBuilderParitySpec`
- `inject-groovy/src/test/groovy/io/micronaut/inject/beanbuilder/BeanElementBuilderMultipleFactorySpec.groovy` - covered: Scala multiple generated factories with qualifiers and injected factory parameters are covered by `ScalaBeanElementBuilderParitySpec`
- `inject-groovy/src/test/groovy/io/micronaut/inject/beanbuilder/BeanElementBuilderSpec.groovy` - candidate: broader visitor-created bean, static creator, interceptor adapter, and type-argument coverage remains beyond the associated bean, generated factory, executable method, and generated AOP cases now covered
- `inject-groovy/src/test/groovy/io/micronaut/inject/beans/AbstractBeanSpec.groovy` - covered: Scala abstract bean definitions with injection points are covered
- `inject-groovy/src/test/groovy/io/micronaut/inject/beans/BeanDefinitionSpec.groovy` - candidate: partially covered for exposed type validation, factory exposed types, top-level and nested bean definition order metadata, uppercase package names, declared bean generics, factory bean generics, resolved type-variable lookups including inherited array type arguments, wildcard generic bounds, and qualifier metadata; remaining bean definition cases should be ported incrementally
- `inject-groovy/src/test/groovy/io/micronaut/inject/configproperties/ConfigPropertiesParseSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/configproperties/ConfigurationPropertiesBuilderSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/configproperties/ImmutableConfigurationPropertiesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/configproperties/InheritedConfigurationReaderPrefixSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/configproperties/InterfaceConfigurationPropertiesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/configproperties/ValidatedConfigurationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/configproperties/VisibilityIssuesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/configuration/GroovyConfigBuilderSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-groovy/src/test/groovy/io/micronaut/inject/context/NoPackageSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-groovy/src/test/groovy/io/micronaut/inject/errors/GroovySingletonSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-groovy/src/test/groovy/io/micronaut/inject/errors/RouteTraitSpec.groovy` - covered: Scala route methods inherited from source-defined traits are exposed as executable methods
- `inject-groovy/src/test/groovy/io/micronaut/inject/executable/ExecutableBeanSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/executable/inheritance/InheritedExecutableSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/factory/FactoryBeanDefinitionSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/factory/FactoryBeanFieldSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/factory/FactoryEnumSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/factory/generics/GenericFactorySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/factory/inheritance/FactoryAbstractInheritanceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/factory/named/ImplicitNamedSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/factory/proxytarget/FactoryWithScopedProxySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/generics/GenericTypeArgumentsSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/generics/TypeArgumentsSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/inheritance/AbstractInheritanceSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/lifecyle/BeanWithPreDestroySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/lifecyle/PostConstructCompileSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/lifecyle/PreDestroyOnBeanAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/property/PropertyWithQualifierSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/qualifiers/MultipleQualifiersSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/qualifiers/NamedSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/qualifiers/repeatable/RepeatableQualifierSpec.groovy` - covered: classpath Java repeatable qualifiers are resolved on Scala constructor injection points, factory methods, and ambiguous dependency lookups
- `inject-groovy/src/test/groovy/io/micronaut/inject/requires/RequiresBeanPropertiesSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/value/ValueParseSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/visitor/AnnotationMetadataSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/visitor/BeanIntrospectionSpec.groovy` - candidate: source comparison for early Scala port after the harness grows beyond the Wave 1 smoke coverage
- `inject-groovy/src/test/groovy/io/micronaut/inject/visitor/ClassElementSpec.groovy` - candidate: partially covered for package element metadata, primitive equality, thrown types, generic type arguments, recursive generic type parameter bounds, wildcard and placeholder metadata, inherited Scala interface type-argument annotations, type visitor queries, inherited method `ElementQuery` filtering, Scala emitted field `ElementQuery` filtering, enum elements, nested classes, and element equality; remaining inherited metadata and broader annotation propagation cases should be ported incrementally
- `inject-groovy/src/test/groovy/io/micronaut/inject/visitor/CustomVisitorSpec.groovy` - covered: basic TypeElementVisitor class/method/property observation is covered by ScalaPoCSpec
- `inject-groovy/src/test/groovy/io/micronaut/inject/visitor/ElementAnnotateSpec.groovy` - candidate: source comparison for early Scala port after the harness grows beyond the Wave 1 smoke coverage
- `inject-groovy/src/test/groovy/io/micronaut/inject/visitor/IntroductionVisitorSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/visitor/PropertyElementSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-groovy/src/test/groovy/io/micronaut/inject/visitor/TypeElementQuerySpec.groovy` - covered: Scala type visitors cover `TypeElementQuery` field, method, and constructor inclusion
- `inject-groovy/src/test/groovy/io/micronaut/validation/ValidatedParseSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above

### Kotlin AbstractKotlinCompilerSpec

- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/annotations/AddsRepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/annotations/AddsUnseenInnerRepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/annotations/AddsUnseenRepeatableAnnotationSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/annotations/AnnotateArraySpec.groovy` - covered: empty array annotation members are covered on Scala executable methods
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/annotations/AnnotateFieldSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/annotations/AnnotateFieldTypeSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/annotations/AnnotateMethodParameterSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/annotations/AnnotateMethodReturnSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/annotations/AnnotateMethodSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/annotations/AnnotatePropertySpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/aop/introduction/MyIsEnumInTypeArgumentSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/aop/introduction/MyRepo3Spec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/ast/visitor/KotlinEnumElementSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/beans/SingletonSpec.groovy` - covered: basic singleton constructor injection is covered by ScalaPoCSpec
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/inject/ast/ClassElementSpec.groovy` - candidate: source comparison for early Scala port after the harness grows beyond the Wave 1 smoke coverage
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/inject/generics/GenericTypeArgumentsSpec.groovy` - candidate: not yet covered; prioritize by the gap buckets above
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/visitor/BeanIntrospectionSpec.groovy` - candidate: source comparison for early Scala port after the harness grows beyond the Wave 1 smoke coverage
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/visitor/KotlinReconstructionSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
- `inject-kotlin/src/test/groovy/io/micronaut/kotlin/processing/visitor/order/VisitorOrderSpec.groovy` - scala-specific: language-specific Java/Groovy/Kotlin syntax or compiler behavior; replace with Scala-native coverage when relevant
