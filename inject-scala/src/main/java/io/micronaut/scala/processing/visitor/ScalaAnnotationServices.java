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

import io.micronaut.core.io.service.ServiceDefinition;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.annotation.AnnotatedElementValidator;
import io.micronaut.inject.annotation.AnnotationMapper;
import io.micronaut.inject.annotation.AnnotationRemapper;
import io.micronaut.inject.annotation.AnnotationTransformer;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.annotation.NamedAnnotationTransformer;
import io.micronaut.inject.annotation.TypedAnnotationMapper;
import io.micronaut.inject.annotation.TypedAnnotationTransformer;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The annotation mappers, transformers, remappers and element validator of one compilation,
 * found on its classpath.
 *
 * <p>{@code AbstractAnnotationMetadataBuilder} finds these once, in a static initializer, through
 * whichever loader loaded it. Under javac that is the processor path, which carries every
 * processor's services alongside Micronaut's own. Here it is the plugin's loader, which sees the
 * plugin jar and the compiler and nothing else, so in a Gradle build a mapper micronaut-openapi
 * ships was never loaded and the element validator micronaut-validation ships never validated
 * an annotation value. In the test harness, where the plugin's loader is the application's, they
 * did load -- once, for the life of the JVM, keeping the annotation classes of whichever
 * compilation ran first, which the validator then compared with the classes of every later one.
 * So they are found again here, per compilation, through the compilation's own classpath, and
 * go away with it. The classpath sees the plugin's bundled services through its parent, so
 * Micronaut's own mappers are still among them.</p>
 */
final class ScalaAnnotationServices {

    private final ClassLoader classLoader;
    private @Nullable Map<String, List<AnnotationMapper>> mappers;
    private @Nullable Map<String, List<AnnotationTransformer>> transformers;
    private @Nullable Map<String, List<AnnotationRemapper>> remappers;
    private final List<AnnotationRemapper> allPackageRemappers = new ArrayList<>(2);
    private boolean elementValidatorLoaded;
    private @Nullable AnnotatedElementValidator elementValidator;

    /**
     * @param classLoader The compilation's processing classloader
     */
    ScalaAnnotationServices(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <K extends Annotation> List<AnnotationMapper<K>> mappers(String annotationName) {
        if (mappers == null) {
            mappers = byName(AnnotationMapper.class, mapper -> {
                if (mapper instanceof TypedAnnotationMapper<?> typed) {
                    return typed.annotationType().getName();
                }
                if (mapper instanceof NamedAnnotationMapper named) {
                    return named.getName();
                }
                return null;
            });
        }
        return (List) mappers.getOrDefault(annotationName, List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <K extends Annotation> List<AnnotationTransformer<K>> transformers(String annotationName) {
        if (transformers == null) {
            transformers = byName(AnnotationTransformer.class, transformer -> {
                if (transformer instanceof TypedAnnotationTransformer<?> typed) {
                    return typed.annotationType().getName();
                }
                if (transformer instanceof NamedAnnotationTransformer named) {
                    return named.getName();
                }
                return null;
            });
        }
        return (List) transformers.getOrDefault(annotationName, List.of());
    }

    List<AnnotationRemapper> remappers(String packageName) {
        if (remappers == null) {
            remappers = byName(AnnotationRemapper.class, remapper -> {
                String name = remapper.getPackageName();
                if (AnnotationRemapper.ALL_PACKAGES.equals(name)) {
                    allPackageRemappers.add(remapper);
                    return null;
                }
                return name;
            });
        }
        List<AnnotationRemapper> forPackage = remappers.get(packageName);
        if (forPackage == null) {
            return allPackageRemappers;
        }
        return CollectionUtils.concat(forPackage, allPackageRemappers);
    }

    @Nullable AnnotatedElementValidator elementValidator() {
        if (!elementValidatorLoaded) {
            elementValidatorLoaded = true;
            for (ServiceDefinition<AnnotatedElementValidator> definition : SoftServiceLoader.load(AnnotatedElementValidator.class, classLoader).disableFork()) {
                try {
                    if (definition.isPresent()) {
                        elementValidator = definition.load();
                        break;
                    }
                } catch (Throwable e) {
                    // A validator whose own dependencies are not on this classpath.
                    rethrowIfFatal(e);
                }
            }
        }
        return elementValidator;
    }

    /**
     * Every service of a kind on the classpath, keyed by the annotation or package it applies
     * to. One that cannot be loaded -- its own dependencies are not on this classpath -- is
     * skipped, as the static registry skips it. Loaded one definition at a time for that
     * reason: {@code collectAll()} fails as a whole when any one of them does.
     */
    private <S> Map<String, List<S>> byName(Class<S> serviceType, Function<S, @Nullable String> keyOf) {
        Map<String, List<S>> services = new HashMap<>();
        for (ServiceDefinition<S> definition : SoftServiceLoader.load(serviceType, classLoader).disableFork()) {
            try {
                if (!definition.isPresent()) {
                    continue;
                }
                S service = definition.load();
                String key = keyOf.apply(service);
                if (StringUtils.isNotEmpty(key)) {
                    services.computeIfAbsent(key, ignored -> new ArrayList<>(2)).add(service);
                }
            } catch (Throwable e) {
                rethrowIfFatal(e);
            }
        }
        return services;
    }

    private static void rethrowIfFatal(Throwable e) {
        if (e instanceof VirtualMachineError error) {
            throw error;
        }
    }
}
