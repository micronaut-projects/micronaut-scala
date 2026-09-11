/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.scala.processing.visitor;

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.expressions.context.DefaultExpressionCompilationContextFactory;
import io.micronaut.expressions.context.ExpressionCompilationContextFactory;
import io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.annotation.ElementAnnotationMetadataFactory;
import io.micronaut.inject.ast.beans.BeanElementBuilder;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.BeanElementVisitorContext;
import io.micronaut.inject.visitor.BeanDefinitionInjectionPointResolver;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.AbstractBeanDefinitionBuilder;
import io.micronaut.inject.writer.DirectoryClassWriterOutputVisitor;
import io.micronaut.inject.writer.GeneratedFile;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Visitor context for Scala compiler plugin processing.
 */
public final class ScalaVisitorContext implements VisitorContext, BeanElementVisitorContext {

    private final MutableConvertibleValues<Object> attributes = new MutableConvertibleValuesMap<>();
    private final File outputDirectory;
    private final DirectoryClassWriterOutputVisitor outputVisitor;
    private final ScalaElementFactory elementFactory = new ScalaElementFactory(this);
    private final ScalaElementAnnotationMetadataFactory annotationMetadataFactory;
    private final ScalaAnnotationMetadataBuilder annotationMetadataBuilder;
    private final BeanDefinitionInjectionPointResolver beanDefinitionInjectionPointResolver = new ScalaBeanDefinitionInjectionPointResolver();
    private final ExpressionCompilationContextFactory expressionCompilationContextFactory = new DefaultExpressionCompilationContextFactory(this);
    private final Map<String, ScalaClassData> sourceClasses = new LinkedHashMap<>();
    private final Map<String, ScalaClassElement> sourceElements = new LinkedHashMap<>();
    /**
     * Scala classes the compiler read from the classpath, once modelled. Keyed by name like the
     * source elements, and for the same reason: an element is identity, and the same type
     * reached twice has to be the same element.
     */
    private final Map<String, Optional<ScalaClassElement>> classpathElements = new HashMap<>();
    /**
     * One element per package. Every class in a package has to answer {@code getPackage()}
     * with the same element, or an annotation added to one class's package is invisible from
     * the next class in it -- and from a second call on the same class.
     */
    private final Map<String, ScalaPackageElement> packageElements = new LinkedHashMap<>();
    private final IdentityHashMap<Object, MutableAnnotationMetadata> elementAnnotationMetadata = new IdentityHashMap<>();
    private final List<AbstractBeanDefinitionBuilder> beanDefinitionBuilders = new ArrayList<>();
    private final Map<String, String> options;
    private final Function<String, ScalaAnnotationTypeData> annotationTypeResolver;
    private final Function<String, ScalaClassData> classpathClassResolver;
    private final BiConsumer<String, Object> infoReporter;
    private final BiConsumer<String, Object> warningReporter;
    private final BiConsumer<String, Object> errorReporter;
    private final ClassLoader classLoader;
    // Only ever used to reach the two-argument getAnnotationType, whose resolutions are
    // cached in a registry shared by every metadata instance.
    private final MutableAnnotationMetadata annotationTypeRegistrar = new MutableAnnotationMetadata();
    // Keyed by the native compiler object each element carries, because that is the only
    // identity shared between the extracted model and the elements built from it. Doc
    // comments live in the compiler's own side table rather than on the trees, so they
    // cannot ride along inside the extracted data the way annotations do.
    private final Map<Object, String> documentation;
    private TypeElementVisitor.VisitorKind visitorKind = TypeElementVisitor.VisitorKind.ISOLATING;

    public ScalaVisitorContext(
        File outputDirectory,
        Collection<ScalaClassData> sourceClasses,
        Collection<File> classpath,
        Map<String, String> options,
        Function<String, ScalaAnnotationTypeData> annotationTypeResolver,
        Function<String, ScalaClassData> classpathClassResolver,
        Map<Object, String> documentation,
        BiConsumer<String, Object> infoReporter,
        BiConsumer<String, Object> warningReporter,
        BiConsumer<String, Object> errorReporter) {
        this.documentation = documentation == null ? Map.of() : documentation;
        this.outputDirectory = outputDirectory;
        this.outputVisitor = new DirectoryClassWriterOutputVisitor(outputDirectory);
        this.options = options == null ? Collections.emptyMap() : Map.copyOf(options);
        this.annotationTypeResolver = annotationTypeResolver;
        this.classpathClassResolver = classpathClassResolver == null ? name -> null : classpathClassResolver;
        this.infoReporter = infoReporter;
        this.warningReporter = warningReporter;
        this.errorReporter = errorReporter;
        this.classLoader = createClassLoader(classpath);
        this.annotationMetadataBuilder = new ScalaAnnotationMetadataBuilder(this);
        this.annotationMetadataFactory = new ScalaElementAnnotationMetadataFactory(annotationMetadataBuilder);
        for (ScalaClassData sourceClass : sourceClasses) {
            this.sourceClasses.put(sourceClass.name(), sourceClass);
        }
    }

    private ClassLoader createClassLoader(Collection<File> classpath) {
        try {
            List<File> files = new ArrayList<>(classpath.size() + 1);
            files.add(outputDirectory);
            files.addAll(classpath);
            URL[] urls = files.stream()
                .map(file -> {
                    try {
                        return file.toURI().toURL();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toArray(URL[]::new);
            return new URLClassLoader(urls, getClass().getClassLoader());
        } catch (RuntimeException e) {
            return getClass().getClassLoader();
        }
    }

    /**
     * The raw documentation comment written on the given native element, if it has one.
     *
     * @param nativeType The native compiler object, as an element reports it
     * @return The raw comment, delimiters included
     */
    Optional<String> documentation(@Nullable Object nativeType) {
        return nativeType == null ? Optional.empty() : Optional.ofNullable(documentation.get(nativeType));
    }

    /**
     * Makes an annotation type loadable by name for anything that asks the metadata for it.
     *
     * <p>{@code AnnotationMetadata.getAnnotationType(String)} resolves through the classloader of
     * the metadata implementation, which here is the plugin jar: it bundles Micronaut, and
     * nothing else. Every annotation an application actually uses -- {@code @Get},
     * {@code @Controller}, its own -- lives on the compilation classpath instead, which the
     * plugin's own classloader cannot see. Java has no such split, because its processor path
     * carries the annotations alongside the processor, so a visitor written against the Element
     * API works there and silently does nothing here: {@code getAnnotationTypeByStereotype}
     * returns empty, and micronaut-openapi took that to mean the method was not an endpoint,
     * generating a specification with no paths at all.</p>
     *
     * <p>The two-argument form takes the classloader to use and caches what it resolves in the
     * shared registry the no-argument form reads, so resolving each annotation once against the
     * compilation classpath is what makes it findable afterwards, whichever classloader the
     * caller's own lookup would have used.</p>
     *
     * @param annotationName The annotation type name
     */
    void makeAnnotationTypeResolvable(String annotationName) {
        annotationTypeRegistrar.getAnnotationType(annotationName, getProcessingClassLoader());
    }

    Optional<ScalaClassElement> sourceClassElement(String name) {
        ScalaClassData classData = sourceClasses.get(name);
        if (classData == null) {
            return Optional.empty();
        }
        return Optional.of(sourceElements.computeIfAbsent(name, ignored -> elementFactory.newClassElementForData(classData)));
    }

    /**
     * Resolves an annotation type by name through the compiler, for annotations that were
     * never seen on an extracted element.
     *
     * @param annotationName The annotation type name
     * @return The annotation type, or {@code null} if it is not an annotation on this classpath
     */
    @Nullable ScalaAnnotationTypeData resolveAnnotationType(String annotationName) {
        return annotationTypeResolver.apply(annotationName);
    }

    Optional<ScalaClassData> sourceClassData(String name) {
        return Optional.ofNullable(sourceClasses.get(name));
    }

    Optional<String> originatingClassName(ScalaAnnotatedElementData element) {
        if (element instanceof ScalaClassData classData) {
            return Optional.of(classData.name());
        }
        if (element instanceof ScalaTypeData typeData) {
            return Optional.of(typeData.name());
        }
        for (ScalaClassData classData : sourceClasses.values()) {
            if (ownsElement(classData, element)) {
                return Optional.of(classData.name());
            }
        }
        return Optional.empty();
    }

    private boolean ownsElement(ScalaClassData classData, ScalaAnnotatedElementData element) {
        for (ScalaMethodData constructor : classData.constructors()) {
            if (ownsMethodElement(constructor, element)) {
                return true;
            }
        }
        for (ScalaMethodData method : classData.methods()) {
            if (ownsMethodElement(method, element)) {
                return true;
            }
        }
        for (ScalaFieldData field : classData.fields()) {
            if (field == element) {
                return true;
            }
        }
        for (ScalaPropertyData property : classData.properties()) {
            if (ownsPropertyElement(property, element)) {
                return true;
            }
        }
        return false;
    }

    private boolean ownsMethodElement(ScalaMethodData method, ScalaAnnotatedElementData element) {
        if (method == element) {
            return true;
        }
        for (ScalaParameterData parameter : method.parameters()) {
            if (parameter == element) {
                return true;
            }
        }
        return false;
    }

    private boolean ownsPropertyElement(ScalaPropertyData property, ScalaAnnotatedElementData element) {
        return property == element
            || property.readMethod() == element
            || property.writeMethod() == element
            || property.field() == element
            || (property.readMethod() != null && ownsMethodElement(property.readMethod(), element))
            || (property.writeMethod() != null && ownsMethodElement(property.writeMethod(), element));
    }

    List<ScalaClassElement> sourceClassElementsEnclosedBy(String name) {
        return sourceClasses.values().stream()
            .filter(classData -> name.equals(classData.enclosingTypeName()))
            .map(classData -> sourceClassElement(classData.name()).orElseThrow())
            .toList();
    }

    @Override
    public Language getLanguage() {
        return Language.SCALA;
    }

    @Override
    public ScalaElementFactory getElementFactory() {
        return elementFactory;
    }

    @Override
    public ElementAnnotationMetadataFactory getElementAnnotationMetadataFactory() {
        return annotationMetadataFactory;
    }

    @Override
    public ExpressionCompilationContextFactory getExpressionCompilationContextFactory() {
        return expressionCompilationContextFactory;
    }

    @Override
    public AbstractAnnotationMetadataBuilder<?, ?> getAnnotationMetadataBuilder() {
        return annotationMetadataBuilder;
    }

    @Override
    public BeanDefinitionInjectionPointResolver getBeanDefinitionInjectionPointResolver() {
        return beanDefinitionInjectionPointResolver;
    }

    public ScalaAnnotationMetadataBuilder getScalaAnnotationMetadataBuilder() {
        return annotationMetadataBuilder;
    }

    /**
     * The element for a package, created once and shared.
     *
     * @param packageName The package name
     * @return The package element
     */
    ScalaPackageElement packageElement(String packageName) {
        return packageElements.computeIfAbsent(packageName, name -> new ScalaPackageElement(name, this));
    }

    MutableAnnotationMetadata annotationMetadata(ScalaAnnotatedElementData element) {
        return elementAnnotationMetadata.computeIfAbsent(
            annotationMetadataKey(element),
            ignored -> annotationMetadataBuilder.buildMetadata(element)
        );
    }

    private Object annotationMetadataKey(ScalaAnnotatedElementData element) {
        if (element instanceof ScalaTypeData typeData && typeData.annotatedTypeUse()) {
            return typeData;
        }
        Object nativeType = element.nativeType();
        return nativeType == null ? element : nativeType;
    }

    /**
     * Loads a type named by an annotation member from the compilation classpath.
     *
     * @param name The type name
     * @return The type, or {@code null} when the classpath does not have it
     */
    @Nullable
    Class<?> loadClasspathType(String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    ClassLoader getProcessingClassLoader() {
        return classLoader;
    }

    TypeElementVisitor.VisitorKind getVisitorKind() {
        return visitorKind;
    }

    void setVisitorKind(TypeElementVisitor.VisitorKind visitorKind) {
        this.visitorKind = visitorKind;
    }

    List<AbstractBeanDefinitionBuilder> getBeanElementBuilders() {
        List<AbstractBeanDefinitionBuilder> current = new ArrayList<>(beanDefinitionBuilders);
        beanDefinitionBuilders.clear();
        return current;
    }

    void addBeanDefinitionBuilder(ScalaBeanDefinitionBuilder beanDefinitionBuilder) {
        beanDefinitionBuilders.add(beanDefinitionBuilder);
    }

    @Override
    public BeanElementBuilder addAssociatedBean(Element originatingElement, ClassElement type) {
        return new ScalaBeanDefinitionBuilder(
            originatingElement,
            type,
            annotationMetadataFactory,
            this
        );
    }

    @Override
    public Optional<ClassElement> getClassElement(String name) {
        Optional<ScalaClassElement> sourceElement = sourceClassElement(name);
        if (sourceElement.isPresent()) {
            return Optional.of(sourceElement.get());
        }
        // A class the compiler does not know is not a class this compilation can see, which is
        // the answer javac's model gives as well. Loading it through a classloader instead
        // described it by Java's conventions, which a Scala class does not follow, and could
        // find a stale class file in the output directory from an earlier build.
        return classpathClassElement(name).map(ClassElement.class::cast);
    }

    /**
     * A Scala class the compiler read from the classpath, modelled from the compiler's own
     * symbols rather than by loading it.
     *
     * <p>This is what an incremental build presents: the type was compiled in an earlier run
     * and the compiler reads it from TASTy, into the same symbols a source type has. Loading the
     * class instead described a case class by the tuple accessors it compiles to, because a
     * Scala accessor follows no convention a classloader can recognise. Only a Scala class
     * arrives this way; a Java one is still loaded, since the compiler's reading of a Java
     * class file skips the annotations on its parameters.</p>
     *
     * @param name The class name
     * @return The element, or empty when the compiler does not know the name as a Scala class
     */
    Optional<ScalaClassElement> classpathClassElement(String name) {
        return classpathElements.computeIfAbsent(name, ignored -> {
            ScalaClassData classData = classpathClassResolver.apply(name);
            return classData == null
                ? Optional.empty()
                : Optional.of(elementFactory.newClassElementForData(classData));
        });
    }

    @Override
    public Optional<ClassElement> getClassElement(String name, ElementAnnotationMetadataFactory annotationMetadataFactory) {
        return getClassElement(name);
    }

    @Override
    public ClassElement[] getClassElements(String aPackage, String... stereotypes) {
        return sourceClasses.values().stream()
            .filter(classData -> io.micronaut.core.naming.NameUtils.getPackageName(classData.name()).equals(aPackage))
            .map(classData -> sourceClassElement(classData.name()).orElseThrow())
            .filter(classElement -> stereotypes.length == 0 || java.util.Arrays.stream(stereotypes).anyMatch(classElement::hasStereotype))
            .toArray(ClassElement[]::new);
    }

    @Override
    public Map<String, String> getOptions() {
        return options;
    }

    @Override
    public void info(String message, @Nullable Element element) {
        infoReporter.accept(message, nativeTypeOf(element));
    }

    @Override
    public void info(String message) {
        infoReporter.accept(message, null);
    }

    @Override
    public void fail(String message, @Nullable Element element) {
        errorReporter.accept(message, nativeTypeOf(element));
        throw new ProcessingException(element, message);
    }

    /**
     * Reports an error without throwing.
     *
     * <p>{@link #fail} reports *and* throws, which is right when the caller wants to abandon
     * the unit of work it is in. It is wrong inside a loop over visitors or classes: the
     * throw escapes the loop, so one failing visitor silently prevents every later visitor
     * from running, and the caller that catches the `ProcessingException` then reports the
     * same message a second time. inject-java's reporting does not throw.</p>
     *
     * @param message The message
     * @param element The element the error concerns, if any
     */
    void reportError(String message, @Nullable Element element) {
        errorReporter.accept(message, nativeTypeOf(element));
    }

    @Override
    public void warn(String message, @Nullable Element element) {
        warningReporter.accept(message, nativeTypeOf(element));
    }

    /**
     * The element the caller blamed, reduced to the dotty tree or symbol it was built
     * from. That is the only thing the compiler can turn back into a source position.
     *
     * @param element The element, or {@code null}
     * @return The native type, or {@code null} when there is nothing to point at
     */
    private static @Nullable Object nativeTypeOf(@Nullable Element element) {
        return element == null ? null : element.getNativeType();
    }

    @Override
    public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
        return attributes.get(name, conversionContext);
    }

    @Override
    public Set<String> names() {
        return attributes.names();
    }

    @Override
    public Collection<Object> values() {
        return attributes.values();
    }

    @Override
    public MutableConvertibleValues<Object> put(CharSequence key, @Nullable Object value) {
        attributes.put(key, value);
        return this;
    }

    @Override
    public MutableConvertibleValues<Object> remove(CharSequence key) {
        attributes.remove(key);
        return this;
    }

    @Override
    public MutableConvertibleValues<Object> clear() {
        attributes.clear();
        return this;
    }

    @Override
    public OutputStream visitClass(String classname, Element... originatingElements) throws IOException {
        return outputVisitor.visitClass(classname, originatingElements);
    }

    @Override
    public void visitServiceDescriptor(String type, String classname) {
        outputVisitor.visitServiceDescriptor(type, classname);
    }

    @Override
    public void visitServiceDescriptor(String type, String classname, Element originatingElement) {
        outputVisitor.visitServiceDescriptor(type, classname, originatingElement);
    }

    @Override
    public Optional<GeneratedFile> visitMetaInfFile(String path, Element... originatingElements) {
        return outputVisitor.visitMetaInfFile(path, originatingElements);
    }

    @Override
    public Optional<GeneratedFile> visitGeneratedFile(String path) {
        return outputVisitor.visitGeneratedFile(path);
    }

    @Override
    public Optional<GeneratedFile> visitGeneratedFile(String path, Element... originatingElements) {
        return outputVisitor.visitGeneratedFile(path, originatingElements);
    }

    @Override
    public void finish() {
        outputVisitor.finish();
    }

    @Override
    public Map<String, Set<String>> getServiceEntries() {
        return outputVisitor.getServiceEntries();
    }

    @Override
    public Optional<Path> getClassesOutputPath() {
        return Optional.of(outputDirectory.toPath());
    }
}
