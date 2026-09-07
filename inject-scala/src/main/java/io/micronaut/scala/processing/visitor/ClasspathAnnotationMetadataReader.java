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

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Reads classpath annotation metadata for loaded Scala visitor elements.
 *
 * <p>Reflection cannot see {@link RetentionPolicy#CLASS} annotations, but Micronaut expression
 * contexts use them on annotation types. The Scala compiler plugin still needs that metadata
 * while compiling source that uses classpath annotations.</p>
 */
final class ClasspathAnnotationMetadataReader {

    /**
     * Keyed weakly: the plugin runs inside a compiler-owned classloader that a Gradle or zinc
     * daemon discards between builds, and a strong static key would pin every class this reader
     * has ever seen, and its classloader, for the life of the JVM.
     */
    private static final Map<Class<?>, LoadedClassMetadata> CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

    private ClasspathAnnotationMetadataReader() {
    }

    static List<AnnotationValue<?>> classAnnotations(Class<?> type) {
        return metadata(type).classAnnotations();
    }

    /**
     * The declared defaults of an annotation type's members, keyed by member name.
     *
     * @param type The annotation type
     * @return The defaults
     */
    static Map<String, Object> annotationMemberDefaults(Class<?> type) {
        return metadata(type).memberDefaults();
    }

    static List<AnnotationValue<?>> methodAnnotations(Method method) {
        return metadata(method.getDeclaringClass())
            .methods()
            .getOrDefault(memberKey(method.getName(), Type.getMethodDescriptor(method)), List.of());
    }

    static List<AnnotationValue<?>> constructorAnnotations(Constructor<?> constructor) {
        return metadata(constructor.getDeclaringClass())
            .methods()
            .getOrDefault(memberKey("<init>", Type.getConstructorDescriptor(constructor)), List.of());
    }

    static List<AnnotationValue<?>> fieldAnnotations(Field field) {
        return metadata(field.getDeclaringClass())
            .fields()
            .getOrDefault(memberKey(field.getName(), Type.getDescriptor(field.getType())), List.of());
    }

    static List<AnnotationValue<?>> parameterAnnotations(Executable executable, int parameterIndex) {
        if (parameterIndex < 0) {
            return List.of();
        }
        List<AnnotationValue<?>>[] annotations = metadata(executable.getDeclaringClass())
            .parameters()
            .get(memberKey(executable instanceof Constructor<?> ? "<init>" : executable.getName(), methodDescriptor(executable)));
        if (annotations == null || parameterIndex >= annotations.length) {
            return List.of();
        }
        return annotations[parameterIndex];
    }

    private static LoadedClassMetadata metadata(Class<?> type) {
        LoadedClassMetadata cached = CACHE.get(type);
        if (cached != null) {
            return cached;
        }
        // Read outside the map lock: readMetadata does classpath I/O, and a duplicate read on a
        // race is cheaper than holding the monitor across it.
        LoadedClassMetadata metadata = readMetadata(type);
        CACHE.put(type, metadata);
        return metadata;
    }

    private static LoadedClassMetadata readMetadata(Class<?> type) {
        try (InputStream inputStream = openClassFile(type)) {
            if (inputStream == null) {
                return LoadedClassMetadata.EMPTY;
            }
            MetadataClassVisitor visitor = new MetadataClassVisitor();
            new ClassReader(inputStream).accept(
                visitor,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
            );
            return visitor.toMetadata();
        } catch (IOException | RuntimeException e) {
            return LoadedClassMetadata.EMPTY;
        }
    }

    private static @Nullable InputStream openClassFile(Class<?> type) {
        String resourceName = type.getName().replace('.', '/') + ".class";
        ClassLoader classLoader = type.getClassLoader();
        InputStream inputStream = classLoader == null
            ? ClassLoader.getSystemResourceAsStream(resourceName)
            : classLoader.getResourceAsStream(resourceName);
        if (inputStream == null) {
            inputStream = type.getResourceAsStream('/' + resourceName);
        }
        return inputStream;
    }

    private static String methodDescriptor(Executable executable) {
        if (executable instanceof Method method) {
            return Type.getMethodDescriptor(method);
        }
        return Type.getConstructorDescriptor((Constructor<?>) executable);
    }

    private static MemberKey memberKey(String name, String descriptor) {
        return new MemberKey(name, descriptor);
    }

    private static AnnotationVisitor annotationVisitor(List<AnnotationValue<?>> sink, String descriptor) {
        return new AnnotationValueVisitor(descriptor, sink::add);
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Type type) {
            return new AnnotationClassValue<>(type.getClassName());
        }
        return value;
    }

    private static Object arrayValue(List<Object> values) {
        if (values.isEmpty()) {
            return values.toArray();
        }
        if (values.stream().allMatch(AnnotationClassValue.class::isInstance)) {
            return values.toArray(AnnotationClassValue[]::new);
        }
        if (values.stream().allMatch(AnnotationValue.class::isInstance)) {
            return values.toArray(AnnotationValue[]::new);
        }
        if (values.stream().allMatch(String.class::isInstance)) {
            return values.toArray(String[]::new);
        }
        return values.toArray();
    }

    private static String annotationName(String descriptor) {
        return Type.getType(descriptor).getClassName();
    }

    private static String memberName(@Nullable String name) {
        return name == null ? AnnotationMetadata.VALUE_MEMBER : name;
    }

    /**
     * Raw annotation values as they appear in the class file. Deliberately not resolved
     * metadata: resolution needs the annotation types of the compilation currently running,
     * and this is cached across compilations.
     */
    private record LoadedClassMetadata(
        List<AnnotationValue<?>> classAnnotations,
        Map<MemberKey, List<AnnotationValue<?>>> methods,
        Map<MemberKey, List<AnnotationValue<?>>[]> parameters,
        Map<MemberKey, List<AnnotationValue<?>>> fields,
        Map<String, Object> memberDefaults) {

        private static final LoadedClassMetadata EMPTY = new LoadedClassMetadata(
            List.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of()
        );
    }

    private record MemberKey(String name, String descriptor) {
    }

    private static final class MetadataClassVisitor extends ClassVisitor {
        private final List<AnnotationValue<?>> classAnnotations = new ArrayList<>();
        private final Map<MemberKey, List<AnnotationValue<?>>> methods = new LinkedHashMap<>();
        private final Map<MemberKey, List<AnnotationValue<?>>[]> parameters = new LinkedHashMap<>();
        private final Map<MemberKey, List<AnnotationValue<?>>> fields = new LinkedHashMap<>();
        private final Map<String, Object> memberDefaults = new LinkedHashMap<>();

        private MetadataClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotationVisitor(classAnnotations, descriptor);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, @Nullable String signature, @Nullable Object value) {
            List<AnnotationValue<?>> fieldAnnotations = new ArrayList<>();
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return annotationVisitor(fieldAnnotations, annotationDescriptor);
                }

                @Override
                public void visitEnd() {
                    if (!fieldAnnotations.isEmpty()) {
                        fields.put(memberKey(name, descriptor), List.copyOf(fieldAnnotations));
                    }
                }
            };
        }

        @Override
        @SuppressWarnings("unchecked")
        public MethodVisitor visitMethod(
            int access,
            String name,
            String descriptor,
            @Nullable String signature,
            String @Nullable [] exceptions) {
            List<AnnotationValue<?>> methodAnnotations = new ArrayList<>();
            Map<Integer, List<AnnotationValue<?>>> parameterAnnotations = new LinkedHashMap<>();
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return annotationVisitor(methodAnnotations, annotationDescriptor);
                }

                /**
                 * An annotation member's declared default. dotty's classfile parser records only
                 * a marker for this attribute and discards the value, so the class file is the
                 * only place it can still be read.
                 */
                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return new AnnotationValueVisitor(
                        Type.getDescriptor(Object.class),
                        annotationValue -> {
                            Object value = annotationValue.getValues().get(AnnotationMetadata.VALUE_MEMBER);
                            if (value != null) {
                                memberDefaults.put(name, value);
                            }
                        }
                    );
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String annotationDescriptor, boolean visible) {
                    return annotationVisitor(
                        parameterAnnotations.computeIfAbsent(parameter, ignored -> new ArrayList<>()),
                        annotationDescriptor
                    );
                }

                @Override
                public void visitEnd() {
                    MemberKey key = memberKey(name, descriptor);
                    if (!methodAnnotations.isEmpty()) {
                        methods.put(key, List.copyOf(methodAnnotations));
                    }
                    if (!parameterAnnotations.isEmpty()) {
                        int parameterCount = Type.getArgumentTypes(descriptor).length;
                        List<AnnotationValue<?>>[] byParameter = new List[parameterCount];
                        for (int i = 0; i < parameterCount; i++) {
                            byParameter[i] = List.copyOf(parameterAnnotations.getOrDefault(i, List.of()));
                        }
                        parameters.put(key, byParameter);
                    }
                }
            };
        }

        private LoadedClassMetadata toMetadata() {
            return new LoadedClassMetadata(
                List.copyOf(classAnnotations),
                Map.copyOf(methods),
                Map.copyOf(parameters),
                Map.copyOf(fields),
                Map.copyOf(memberDefaults)
            );
        }
    }

    private static final class AnnotationValueVisitor extends AnnotationVisitor {
        private final String annotationName;
        private final Consumer<AnnotationValue<?>> annotationConsumer;
        private final Map<CharSequence, Object> values = new LinkedHashMap<>();

        private AnnotationValueVisitor(String descriptor, Consumer<AnnotationValue<?>> annotationConsumer) {
            super(Opcodes.ASM9);
            this.annotationName = annotationName(descriptor);
            this.annotationConsumer = annotationConsumer;
        }

        @Override
        public void visit(String name, Object value) {
            values.put(memberName(name), normalizeValue(value));
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            values.put(memberName(name), value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            return new AnnotationValueVisitor(descriptor, annotationValue ->
                values.put(memberName(name), annotationValue)
            );
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return new ArrayValueVisitor(valuesArray ->
                values.put(memberName(name), arrayValue(valuesArray))
            );
        }

        @Override
        public void visitEnd() {
            annotationConsumer.accept(AnnotationValue.builder(annotationName).members(values).build());
        }
    }

    private static final class ArrayValueVisitor extends AnnotationVisitor {
        private final Consumer<List<Object>> endConsumer;
        private final List<Object> values = new ArrayList<>();

        private ArrayValueVisitor(Consumer<List<Object>> endConsumer) {
            super(Opcodes.ASM9);
            this.endConsumer = endConsumer;
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            values.add(normalizeValue(value));
        }

        @Override
        public void visitEnum(@Nullable String name, String descriptor, String value) {
            values.add(value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(@Nullable String name, String descriptor) {
            return new AnnotationValueVisitor(descriptor, values::add);
        }

        @Override
        public AnnotationVisitor visitArray(@Nullable String name) {
            return new ArrayValueVisitor(nestedValues -> values.add(arrayValue(nestedValues)));
        }

        @Override
        public void visitEnd() {
            endConsumer.accept(values);
        }
    }
}
