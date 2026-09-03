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
package io.micronaut.scala.processing.test;

import dotty.tools.dotc.Main;
import dotty.tools.dotc.reporting.Diagnostic;
import dotty.tools.dotc.reporting.Reporter;
import io.micronaut.aop.internal.InterceptorRegistryBean;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanDefinitionsProvider;
import io.micronaut.context.DefaultBeanDefinitionsProvider;
import io.micronaut.context.event.ApplicationEventPublisherFactory;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.provider.BeanProviderDefinition;
import io.micronaut.inject.provider.JakartaProviderBeanDefinition;
import io.micronaut.inject.writer.BeanDefinitionWriter;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import scala.jdk.javaapi.CollectionConverters;

/**
 * Scala compiler helper for inline test sources.
 */
public final class ScalaCompiler {

    private ScalaCompiler() {
    }

    /**
     * Builds a classloader for a Scala source.
     *
     * @param className The primary class name
     * @param source The source
     * @return The classloader
     */
    public static URLClassLoader buildClassLoader(String className, String source) {
        return compile(className, source, classElement -> {
        }).classLoader();
    }

    /**
     * Builds a class element for the given source.
     *
     * @param className The class name
     * @param source The source
     * @return The class element
     */
    public static @Nullable ClassElement buildClassElement(String className, String source) {
        return buildClassElement(className, source, List.of());
    }

    /**
     * Builds a class element for the given source.
     *
     * @param className The class name
     * @param source The source
     * @param compilerOptions The additional compiler options
     * @return The class element
     */
    public static @Nullable ClassElement buildClassElement(String className, String source, List<String> compilerOptions) {
        List<ClassElement> elements = new ArrayList<>();
        compile(className, source, compilerOptions, elements::add);
        return elements.stream().filter(element -> element.getName().equals(className)).findFirst().orElse(null);
    }

    /**
     * Builds a bean introspection for the given source.
     *
     * @param className The class name
     * @param source The source
     * @return The introspection
     */
    public static @Nullable BeanIntrospection<?> buildBeanIntrospection(String className, String source) {
        return buildBeanIntrospection(className, source, List.of());
    }

    /**
     * Builds a bean introspection for the given source.
     *
     * @param className The class name
     * @param source The source
     * @param compilerOptions The additional compiler options
     * @return The introspection
     */
    public static @Nullable BeanIntrospection<?> buildBeanIntrospection(String className, String source, List<String> compilerOptions) {
        URLClassLoader classLoader = compile(className, source, compilerOptions, classElement -> {
        }).classLoader();
        String beanDefName = "$" + NameUtils.getSimpleName(className) + "$Introspection";
        String packageName = NameUtils.getPackageName(className);
        String introspectionName = packageName + "." + beanDefName;
        try {
            return (BeanIntrospection<?>) classLoader.loadClass(introspectionName).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Builds a bean definition for the given source.
     *
     * @param className The class name
     * @param source The source
     * @return The bean definition
     */
    public static @Nullable BeanDefinition<?> buildBeanDefinition(String className, String source) {
        URLClassLoader classLoader = buildClassLoader(className, source);
        String simpleName = NameUtils.getSimpleName(className);
        String beanDefName = (simpleName.startsWith("$") ? "" : "$") + simpleName + BeanDefinitionWriter.CLASS_SUFFIX;
        String beanFullName = NameUtils.getPackageName(className) + "." + beanDefName;
        try {
            return (BeanDefinition<?>) loadDefinition(classLoader, beanFullName);
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds a bean definition reference for the given source.
     *
     * @param className The class name
     * @param source The source
     * @return The bean definition reference
     */
    public static @Nullable BeanDefinitionReference<?> buildBeanDefinitionReference(String className, String source) {
        URLClassLoader classLoader = buildClassLoader(className, source);
        String simpleName = NameUtils.getSimpleName(className);
        String beanDefName = (simpleName.startsWith("$") ? "" : "$") + simpleName + BeanDefinitionWriter.CLASS_SUFFIX;
        String beanFullName = NameUtils.getPackageName(className) + "." + beanDefName;
        try {
            return (BeanDefinitionReference<?>) loadDefinition(classLoader, beanFullName);
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds an application context for the given source.
     *
     * @param source The source
     * @param includeAllBeans Whether to include all default beans
     * @return The started context
     */
    public static ApplicationContext buildContext(String source, boolean includeAllBeans) {
        return buildContext(source, includeAllBeans, Map.of());
    }

    /**
     * Builds an application context for the given source.
     *
     * @param source The source
     * @param includeAllBeans Whether to include all default beans
     * @param config The context configuration
     * @return The started context
     */
    public static ApplicationContext buildContext(String source, boolean includeAllBeans, Map<String, Object> config) {
        Compilation compilation = compile("test.Source", source, classElement -> {
        });
        URLClassLoader classLoader = compilation.classLoader();
        return ApplicationContext.builder()
            .classLoader(classLoader)
            .environments("test")
            .properties(config)
            .beanDefinitionsProvider(new BeanDefinitionsProvider() {
                @Override
                public List<BeanDefinitionReference<?>> provide(ClassLoader ignored) {
                    List<BeanDefinitionReference<?>> references = new ArrayList<>();
                    for (String beanDefinitionName : beanDefinitionNames(compilation.outputDirectory())) {
                        try {
                            BeanDefinitionReference<?> reference = (BeanDefinitionReference<?>) loadDefinition(classLoader, beanDefinitionName);
                            if (reference != null) {
                                references.add(reference);
                            }
                        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException ignoredException) {
                            // Ignore invalid generated definitions in the lightweight harness.
                        }
                    }
                    if (includeAllBeans) {
                        references.addAll(new DefaultBeanDefinitionsProvider().provide(classLoader));
                    } else {
                        references.add(new InterceptorRegistryBean());
                        references.add(new BeanProviderDefinition());
                        references.add(new JakartaProviderBeanDefinition());
                        references.add(new ApplicationEventPublisherFactory<>());
                    }
                    return references;
                }
            })
            .start();
    }

    static Compilation compile(String className, String source, Consumer<ClassElement> classElementConsumer) {
        return compile(className, source, List.of(), classElementConsumer);
    }

    static Compilation compile(String className, String source, List<String> compilerOptions, Consumer<ClassElement> classElementConsumer) {
        try {
            Path workDirectory = Files.createTempDirectory("micronaut-scala-test");
            Path sourceDirectory = Files.createDirectories(workDirectory.resolve("src"));
            Path outputDirectory = Files.createDirectories(workDirectory.resolve("classes"));
            Path sourceFile = sourceDirectory.resolve(NameUtils.getSimpleName(className) + ".scala");
            Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
            ScalaClassElementCaptureVisitor.withConsumer(classElementConsumer, () -> compileSource(outputDirectory, sourceFile, compilerOptions));
            return new Compilation(outputDirectory, newClassLoader(outputDirectory));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void compileSource(Path outputDirectory, Path sourceFile, List<String> compilerOptions) {
        String classpath = System.getProperty("micronaut.scala.test.classpath");
        String pluginJar = System.getProperty("micronaut.scala.plugin.jar");
        if (classpath == null || pluginJar == null) {
            throw new IllegalStateException("Scala test classpath or plugin jar system property is missing");
        }
        List<String> arguments = new ArrayList<>();
        arguments.add("-classpath");
        arguments.add(classpath);
        arguments.add("-d");
        arguments.add(outputDirectory.toString());
        arguments.add("-release:25");
        arguments.add("-Xplugin:" + pluginJar);
        arguments.addAll(compilerOptions);
        arguments.add(sourceFile.toString());
        Reporter reporter = Main.process(arguments.toArray(String[]::new));
        if (reporter.hasErrors()) {
            throw new IllegalStateException(errorMessage(reporter));
        }
    }

    private static String errorMessage(Reporter reporter) {
        String diagnostics = CollectionConverters.asJava(reporter.allErrors()).stream()
            .map(Diagnostic::message)
            .collect(Collectors.joining(System.lineSeparator()));
        if (diagnostics.isBlank()) {
            return reporter.summary();
        }
        return diagnostics + System.lineSeparator() + reporter.summary();
    }

    private static URLClassLoader newClassLoader(Path outputDirectory) {
        String classpath = System.getProperty("micronaut.scala.test.classpath");
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(outputDirectory.toUri().toURL());
            for (String path : classpathEntries(classpath)) {
                urls.add(new File(path).toURI().toURL());
            }
            return new URLClassLoader(urls.toArray(URL[]::new), ScalaCompiler.class.getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> classpathEntries(String classpath) {
        List<String> entries = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= classpath.length(); i++) {
            if (i == classpath.length() || classpath.charAt(i) == File.pathSeparatorChar) {
                if (i > start) {
                    entries.add(classpath.substring(start, i));
                }
                start = i + 1;
            }
        }
        return entries;
    }

    private static List<String> beanDefinitionNames(Path outputDirectory) {
        Path serviceDirectory = outputDirectory.resolve("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference");
        if (!Files.isDirectory(serviceDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(serviceDirectory)) {
            return files.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static @Nullable Object loadDefinition(ClassLoader classLoader, String name) throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        try {
            Class<?> c = classLoader.loadClass(name);
            Constructor<?> constructor = c.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Compilation result.
     *
     * @param outputDirectory The compiler output directory
     * @param classLoader The classloader
     */
    record Compilation(Path outputDirectory, URLClassLoader classLoader) {
    }
}
