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
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Reads classpath annotation metadata for loaded Scala visitor elements.
 *
 * <p>Reflection cannot see {@link RetentionPolicy#CLASS} annotations, but Micronaut expression
 * contexts use them on annotation types. The Scala compiler plugin still needs that metadata
 * while compiling source that uses classpath annotations.</p>
 */
final class ClasspathAnnotationMetadataReader {

    private static final ConcurrentMap<Class<?>, LoadedClassMetadata> CACHE = new ConcurrentHashMap<>();

    private ClasspathAnnotationMetadataReader() {
    }

    static AnnotationMetadata classMetadata(Class<?> type) {
        return metadata(type).classMetadata();
    }

    static AnnotationMetadata methodMetadata(Method method) {
        return metadata(method.getDeclaringClass())
            .methods()
            .getOrDefault(memberKey(method.getName(), Type.getMethodDescriptor(method)), AnnotationMetadata.EMPTY_METADATA);
    }

    static AnnotationMetadata constructorMetadata(Constructor<?> constructor) {
        return metadata(constructor.getDeclaringClass())
            .methods()
            .getOrDefault(memberKey("<init>", Type.getConstructorDescriptor(constructor)), AnnotationMetadata.EMPTY_METADATA);
    }

    static AnnotationMetadata fieldMetadata(Field field) {
        return metadata(field.getDeclaringClass())
            .fields()
            .getOrDefault(memberKey(field.getName(), Type.getDescriptor(field.getType())), AnnotationMetadata.EMPTY_METADATA);
    }

    static AnnotationMetadata parameterMetadata(Executable executable, int parameterIndex) {
        AnnotationMetadata[] metadata = metadata(executable.getDeclaringClass())
            .parameters()
            .get(memberKey(executable instanceof Constructor<?> ? "<init>" : executable.getName(), methodDescriptor(executable)));
        if (metadata == null || parameterIndex >= metadata.length) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        return metadata[parameterIndex];
    }

    private static LoadedClassMetadata metadata(Class<?> type) {
        return CACHE.computeIfAbsent(type, ClasspathAnnotationMetadataReader::readMetadata);
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

    private static AnnotationVisitor annotationVisitor(
        MutableAnnotationMetadata annotationMetadata,
        String descriptor,
        boolean visible) {
        return new AnnotationValueVisitor(descriptor, annotationValue ->
            annotationMetadata.addDeclaredAnnotation(
                annotationValue.getAnnotationName(),
                annotationValue.getValues(),
                visible ? RetentionPolicy.RUNTIME : RetentionPolicy.CLASS
            )
        );
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

    private record LoadedClassMetadata(
        AnnotationMetadata classMetadata,
        Map<MemberKey, AnnotationMetadata> methods,
        Map<MemberKey, AnnotationMetadata[]> parameters,
        Map<MemberKey, AnnotationMetadata> fields) {

        private static final LoadedClassMetadata EMPTY = new LoadedClassMetadata(
            AnnotationMetadata.EMPTY_METADATA,
            Map.of(),
            Map.of(),
            Map.of()
        );
    }

    private record MemberKey(String name, String descriptor) {
    }

    private static final class MetadataClassVisitor extends ClassVisitor {
        private final MutableAnnotationMetadata classMetadata = new MutableAnnotationMetadata();
        private final Map<MemberKey, AnnotationMetadata> methods = new LinkedHashMap<>();
        private final Map<MemberKey, AnnotationMetadata[]> parameters = new LinkedHashMap<>();
        private final Map<MemberKey, AnnotationMetadata> fields = new LinkedHashMap<>();

        private MetadataClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotationVisitor(classMetadata, descriptor, visible);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, @Nullable String signature, @Nullable Object value) {
            MutableAnnotationMetadata fieldMetadata = new MutableAnnotationMetadata();
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return annotationVisitor(fieldMetadata, annotationDescriptor, visible);
                }

                @Override
                public void visitEnd() {
                    if (!fieldMetadata.isEmpty()) {
                        fields.put(memberKey(name, descriptor), fieldMetadata);
                    }
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
            int access,
            String name,
            String descriptor,
            @Nullable String signature,
            String @Nullable [] exceptions) {
            MutableAnnotationMetadata methodMetadata = new MutableAnnotationMetadata();
            Map<Integer, MutableAnnotationMetadata> parameterMetadata = new LinkedHashMap<>();
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return annotationVisitor(methodMetadata, annotationDescriptor, visible);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String annotationDescriptor, boolean visible) {
                    return annotationVisitor(
                        parameterMetadata.computeIfAbsent(parameter, ignored -> new MutableAnnotationMetadata()),
                        annotationDescriptor,
                        visible
                    );
                }

                @Override
                public void visitEnd() {
                    MemberKey key = memberKey(name, descriptor);
                    if (!methodMetadata.isEmpty()) {
                        methods.put(key, methodMetadata);
                    }
                    if (!parameterMetadata.isEmpty()) {
                        int parameterCount = Type.getArgumentTypes(descriptor).length;
                        AnnotationMetadata[] metadata = new AnnotationMetadata[parameterCount];
                        for (int i = 0; i < parameterCount; i++) {
                            metadata[i] = parameterMetadata.getOrDefault(i, new MutableAnnotationMetadata());
                        }
                        parameters.put(key, metadata);
                    }
                }
            };
        }

        private LoadedClassMetadata toMetadata() {
            return new LoadedClassMetadata(
                classMetadata.isEmpty() ? AnnotationMetadata.EMPTY_METADATA : classMetadata,
                Map.copyOf(methods),
                Map.copyOf(parameters),
                Map.copyOf(fields)
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
