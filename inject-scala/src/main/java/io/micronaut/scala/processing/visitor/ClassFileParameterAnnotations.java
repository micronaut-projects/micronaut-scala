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

import org.jspecify.annotations.Nullable;

import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.AnnotationDefaultAttribute;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.reflect.AccessFlag;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The annotations on the parameters of a Java class file's methods.
 *
 * <p>The Scala compiler reads a Java class from its class file into the same symbols a
 * Scala class has, and that reading is what the rest of the model is built from. It leaves
 * one thing out: dotty's class-file parser does not read {@code RuntimeVisibleParameterAnnotations}
 * or its invisible counterpart at all -- a documented TODO -- and parameter annotations are
 * where Micronaut looks most. {@code @Nullable} decides whether an argument may be absent,
 * {@code @Body} and {@code @QueryValue} decide where it comes from, and {@code @Id} on a
 * repository method's parameter decides what it means.</p>
 *
 * <p>This reads exactly that, from the bytes the compiler already has for the class, with the
 * JDK's own class-file API. Nothing is loaded: reading bytes has no static initialiser to run
 * and no classloader to choose, which is what the reflective reading it replaces got wrong
 * whenever the plugin's classloader and the application's disagreed.</p>
 *
 * <p>Type annotations are read as well, where they sit on a parameter's own type, a return
 * type or a field's type. Nullability in Java has moved there: jspecify's {@code @Nullable} is
 * {@code TYPE_USE} only, so {@code body(@Nullable T body)} records nothing in the parameter
 * annotations and everything in the type annotations, and javac's model reports it on the
 * parameter regardless. Annotations deeper in a type -- on a type argument -- are left where
 * they are.</p>
 *
 * <p>The values come out as plain data -- constants, names, and the records below -- and are
 * turned into the model's own annotation data by the extractor, which is where the annotation
 * types are resolved.</p>
 */
public final class ClassFileParameterAnnotations {

    private static final String CONSTRUCTOR = "<init>";

    private final Map<String, List<MethodModel>> methodsByName = new LinkedHashMap<>();
    private final Map<String, FieldModel> fieldsByName = new LinkedHashMap<>();

    private ClassFileParameterAnnotations(ClassModel classModel) {
        for (MethodModel method : classModel.methods()) {
            methodsByName.computeIfAbsent(method.methodName().stringValue(), ignored -> new ArrayList<>()).add(method);
        }
        for (FieldModel field : classModel.fields()) {
            fieldsByName.putIfAbsent(field.fieldName().stringValue(), field);
        }
    }

    /**
     * Reads a class file.
     *
     * @param bytes The class file
     * @return Its parameter annotations
     */
    public static ClassFileParameterAnnotations parse(byte[] bytes) {
        return new ClassFileParameterAnnotations(ClassFile.of().parse(bytes));
    }

    /**
     * The annotations of a method the compiler did not read: on its parameters, and on its
     * return type and parameter types.
     *
     * <p>The method is found by name and by the erased types of its parameters, which is what
     * a JVM descriptor holds. An overload set is disambiguated that way; a single method of
     * the name and arity is taken as is, so a mismatch in how an erasure is spelled cannot
     * lose the annotations of an unambiguous method.</p>
     *
     * <p>A constructor of an inner class takes the enclosing instance as a leading parameter
     * the language does not show, and the compiler's model of it does not either. When no
     * constructor matches as declared, one whose descriptor has exactly that one extra leading
     * parameter is accepted and its first parameter ignored.</p>
     *
     * @param name The method name, {@code <init>} for a constructor
     * @param erasedParameterTypes The erased parameter types, as binary names
     * @return The annotations, {@link MethodAnnotations#NONE} when the method is not found
     */
    public MethodAnnotations forMethod(String name, List<String> erasedParameterTypes) {
        List<MethodModel> candidates = methodsByName.getOrDefault(name, List.of());
        MethodModel match = matching(candidates, erasedParameterTypes, 0);
        int skip = 0;
        if (match == null && CONSTRUCTOR.equals(name)) {
            match = matching(candidates, erasedParameterTypes, 1);
            skip = 1;
        }
        if (match == null) {
            return MethodAnnotations.NONE;
        }
        List<List<Annotation>> parameters = new ArrayList<>();
        for (int i = 0; i < erasedParameterTypes.size(); i++) {
            parameters.add(new ArrayList<>());
        }
        for (List<List<java.lang.classfile.Annotation>> perParameter : parameterAnnotations(match)) {
            for (int i = skip; i < perParameter.size() && i - skip < parameters.size(); i++) {
                for (java.lang.classfile.Annotation annotation : perParameter.get(i)) {
                    parameters.get(i - skip).add(annotation(annotation));
                }
            }
        }
        List<Annotation> returnType = new ArrayList<>();
        for (TypeAnnotation typeAnnotation : typeAnnotations(match)) {
            // Only an annotation on the type itself. One on a type argument -- `List<@Nullable
            // String>` -- has a non-empty path and describes the argument, not the parameter.
            if (!typeAnnotation.targetPath().isEmpty()) {
                continue;
            }
            switch (typeAnnotation.targetInfo()) {
                case TypeAnnotation.FormalParameterTarget target -> {
                    int index = target.formalParameterIndex() - skip;
                    if (index >= 0 && index < parameters.size()) {
                        parameters.get(index).add(annotation(typeAnnotation.annotation()));
                    }
                }
                case TypeAnnotation.EmptyTarget target when target.targetType() == TypeAnnotation.TargetType.METHOD_RETURN ->
                    returnType.add(annotation(typeAnnotation.annotation()));
                default -> {
                }
            }
        }
        MethodAnnotations result = new MethodAnnotations(returnType, parameters);
        return result.isEmpty() ? MethodAnnotations.NONE : result;
    }

    /**
     * The private fields of the class, which the compiler does not enter at all.
     *
     * <p>dotty's class-file parser skips every private member of a Java class -- a Scala
     * program cannot refer to one, so the compiler has no use for it. Micronaut does: an
     * {@code @Inject} field or a {@code @PostConstruct} method in a library's base class may
     * well be private, and the Java model shows both. They are read from the class file
     * instead, with their generic signatures, so the model can build the same types for them
     * it builds for everything else.</p>
     *
     * @return The private fields, in class-file order
     */
    public List<PrivateField> privateFields() {
        List<PrivateField> result = new ArrayList<>();
        for (FieldModel field : fieldsByName.values()) {
            if (!field.flags().has(AccessFlag.PRIVATE) || field.flags().has(AccessFlag.SYNTHETIC)) {
                continue;
            }
            String descriptor = field.fieldType().stringValue();
            Signature signature = field.findAttribute(Attributes.signature())
                .map(SignatureAttribute::asTypeSignature)
                .orElseGet(() -> Signature.parseFrom(descriptor));
            Object constant = field.findAttribute(Attributes.constantValue())
                .map(ConstantValueAttribute::constant)
                .map(entry -> entry.constantValue())
                .orElse(null);
            result.add(new PrivateField(
                field.fieldName().stringValue(),
                signature,
                field.flags().has(AccessFlag.STATIC),
                field.flags().has(AccessFlag.FINAL),
                constant,
                declaredAnnotations(field),
                forField(field.fieldName().stringValue())
            ));
        }
        return result;
    }

    /**
     * The private methods of the class, which the compiler does not enter at all; see
     * {@link #privateFields()}. Constructors, bridges and synthetics are not included.
     *
     * @return The private methods, in class-file order
     */
    public List<PrivateMethod> privateMethods() {
        List<PrivateMethod> result = new ArrayList<>();
        for (List<MethodModel> overloads : methodsByName.values()) {
            for (MethodModel method : overloads) {
                String name = method.methodName().stringValue();
                if (!method.flags().has(AccessFlag.PRIVATE)
                    || method.flags().has(AccessFlag.SYNTHETIC)
                    || method.flags().has(AccessFlag.BRIDGE)
                    || CONSTRUCTOR.equals(name)
                    || "<clinit>".equals(name)) {
                    continue;
                }
                String descriptor = method.methodType().stringValue();
                MethodSignature signature = method.findAttribute(Attributes.signature())
                    .map(SignatureAttribute::asMethodSignature)
                    .orElseGet(() -> MethodSignature.parseFrom(descriptor));
                List<String> parameterNames = method.findAttribute(Attributes.methodParameters())
                    .map(MethodParametersAttribute::parameters)
                    .map(parameters -> parameters.stream()
                        .map(parameter -> parameter.name().map(entry -> entry.stringValue()).orElse(null))
                        .toList())
                    .orElse(List.of());
                List<String> erased = method.methodTypeSymbol().parameterList().stream()
                    .map(ClassFileParameterAnnotations::binaryName)
                    .toList();
                result.add(new PrivateMethod(
                    name,
                    signature,
                    parameterNames,
                    method.flags().has(AccessFlag.STATIC),
                    declaredAnnotations(method),
                    forMethod(name, erased)
                ));
            }
        }
        return result;
    }

    private static List<Annotation> declaredAnnotations(java.lang.classfile.AttributedElement element) {
        List<Annotation> result = new ArrayList<>();
        element.findAttribute(Attributes.runtimeVisibleAnnotations())
            .map(RuntimeVisibleAnnotationsAttribute::annotations)
            .ifPresent(annotations -> annotations.forEach(annotation -> result.add(annotation(annotation))));
        element.findAttribute(Attributes.runtimeInvisibleAnnotations())
            .map(RuntimeInvisibleAnnotationsAttribute::annotations)
            .ifPresent(annotations -> annotations.forEach(annotation -> result.add(annotation(annotation))));
        return result;
    }

    /**
     * The default value of each member of an annotation type, from its {@code AnnotationDefault}
     * attributes. The compiler records that a member has a default and discards the value; the
     * value is what a use of the annotation that omits the member means.
     *
     * @return The defaults by member name, as {@link Annotation} values are represented
     */
    public Map<String, Object> annotationDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (List<MethodModel> overloads : methodsByName.values()) {
            for (MethodModel method : overloads) {
                method.findAttribute(Attributes.annotationDefault())
                    .map(AnnotationDefaultAttribute::defaultValue)
                    .ifPresent(value -> defaults.put(method.methodName().stringValue(), value(value)));
            }
        }
        return defaults;
    }

    /**
     * The type annotations on a field's type, which the compiler does not read.
     *
     * @param name The field name
     * @return The annotations, empty when there are none or the field is not found
     */
    public List<Annotation> forField(String name) {
        FieldModel field = fieldsByName.get(name);
        if (field == null) {
            return List.of();
        }
        List<Annotation> result = new ArrayList<>();
        for (TypeAnnotation typeAnnotation : typeAnnotations(field)) {
            if (typeAnnotation.targetPath().isEmpty()
                && typeAnnotation.targetInfo() instanceof TypeAnnotation.EmptyTarget target
                && target.targetType() == TypeAnnotation.TargetType.FIELD) {
                result.add(annotation(typeAnnotation.annotation()));
            }
        }
        return result;
    }

    private static List<TypeAnnotation> typeAnnotations(java.lang.classfile.AttributedElement element) {
        List<TypeAnnotation> result = new ArrayList<>();
        element.findAttribute(Attributes.runtimeVisibleTypeAnnotations())
            .map(RuntimeVisibleTypeAnnotationsAttribute::annotations)
            .ifPresent(result::addAll);
        element.findAttribute(Attributes.runtimeInvisibleTypeAnnotations())
            .map(RuntimeInvisibleTypeAnnotationsAttribute::annotations)
            .ifPresent(result::addAll);
        return result;
    }

    private static @Nullable MethodModel matching(List<MethodModel> candidates, List<String> erasedParameterTypes, int leading) {
        List<MethodModel> byArity = candidates.stream()
            .filter(method -> method.methodTypeSymbol().parameterCount() == erasedParameterTypes.size() + leading)
            .toList();
        if (byArity.size() == 1 && leading == 0) {
            return byArity.get(0);
        }
        for (MethodModel method : byArity) {
            List<ClassDesc> parameters = method.methodTypeSymbol().parameterList();
            boolean same = true;
            for (int i = 0; i < erasedParameterTypes.size() && same; i++) {
                same = binaryName(parameters.get(i + leading)).equals(erasedParameterTypes.get(i));
            }
            if (same) {
                return method;
            }
        }
        return null;
    }

    private static List<List<List<java.lang.classfile.Annotation>>> parameterAnnotations(MethodModel method) {
        List<List<List<java.lang.classfile.Annotation>>> attributes = new ArrayList<>(2);
        method.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
            .map(RuntimeVisibleParameterAnnotationsAttribute::parameterAnnotations)
            .ifPresent(attributes::add);
        method.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
            .map(RuntimeInvisibleParameterAnnotationsAttribute::parameterAnnotations)
            .ifPresent(attributes::add);
        return attributes;
    }

    private static Annotation annotation(java.lang.classfile.Annotation annotation) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (AnnotationElement element : annotation.elements()) {
            values.put(element.name().stringValue(), value(element.value()));
        }
        return new Annotation(binaryName(annotation.classSymbol()), values);
    }

    private static Object value(AnnotationValue value) {
        return switch (value) {
            case AnnotationValue.OfConstant constant -> constant.resolvedValue();
            case AnnotationValue.OfEnum enumValue ->
                new EnumValue(binaryName(enumValue.classSymbol()), enumValue.constantName().stringValue());
            case AnnotationValue.OfClass classValue -> new ClassLiteral(binaryName(classValue.classSymbol()));
            case AnnotationValue.OfArray array -> array.values().stream().map(ClassFileParameterAnnotations::value).toList();
            case AnnotationValue.OfAnnotation nested -> annotation(nested.annotation());
        };
    }

    /**
     * The binary name a descriptor denotes: {@code java.lang.String}, {@code int},
     * {@code pkg.Outer$Inner}, {@code java.lang.String[]}.
     *
     * @param descriptor The descriptor
     * @return The binary name
     */
    static String binaryName(ClassDesc descriptor) {
        if (descriptor.isArray()) {
            return binaryName(descriptor.componentType()) + "[]";
        }
        if (descriptor.isPrimitive()) {
            return descriptor.displayName();
        }
        String text = descriptor.descriptorString();
        return text.substring(1, text.length() - 1).replace('/', '.');
    }

    /**
     * A private field the compiler did not enter.
     *
     * @param name The field name
     * @param signature Its generic signature, or its descriptor when it has none
     * @param isStatic Whether it is static
     * @param isFinal Whether it is final
     * @param constantValue Its {@code ConstantValue}, if any
     * @param annotations The annotations on the field
     * @param typeAnnotations The type annotations on its type
     */
    public record PrivateField(
        String name,
        Signature signature,
        boolean isStatic,
        boolean isFinal,
        @Nullable Object constantValue,
        List<Annotation> annotations,
        List<Annotation> typeAnnotations) {
    }

    /**
     * A private method the compiler did not enter.
     *
     * @param name The method name
     * @param signature Its generic signature, or its descriptor when it has none
     * @param parameterNames The parameter names from {@code MethodParameters}, empty when absent
     * @param isStatic Whether it is static
     * @param annotations The annotations on the method
     * @param parameterAnnotations Its parameter and type annotations
     */
    public record PrivateMethod(
        String name,
        MethodSignature signature,
        List<@Nullable String> parameterNames,
        boolean isStatic,
        List<Annotation> annotations,
        MethodAnnotations parameterAnnotations) {
    }

    /**
     * What a method's class file says about its annotations beyond what the compiler read.
     *
     * @param returnType Type annotations on the return type
     * @param parameters Per parameter, in order: its parameter annotations and the type
     *     annotations on its type; empty lists where there are none
     */
    public record MethodAnnotations(List<Annotation> returnType, List<List<Annotation>> parameters) {

        /** A method that was not found, or annotates nothing this reads. */
        public static final MethodAnnotations NONE = new MethodAnnotations(List.of(), List.of());

        /**
         * @return Whether anything was read
         */
        public boolean isEmpty() {
            return returnType.isEmpty() && parameters.stream().allMatch(List::isEmpty);
        }
    }

    /**
     * An annotation as written in the class file.
     *
     * @param typeName The annotation type's binary name
     * @param values The member values, by member name; each a boxed constant, a {@link String},
     *     an {@link EnumValue}, a {@link ClassLiteral}, a nested {@code Annotation}, or a
     *     {@link List} of those
     */
    public record Annotation(String typeName, Map<String, Object> values) {
    }

    /**
     * An enum constant used as an annotation value.
     *
     * @param typeName The enum type's binary name
     * @param constantName The constant's name
     */
    public record EnumValue(String typeName, String constantName) {
    }

    /**
     * A class literal used as an annotation value.
     *
     * @param typeName The class's binary name; a primitive by its keyword, an array with
     *     {@code []} per dimension
     */
    public record ClassLiteral(String typeName) {
    }
}
