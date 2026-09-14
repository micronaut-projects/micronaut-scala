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

import io.micronaut.context.annotation.Primary;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.beans.BeanElementBuilder;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.NonNull;

import java.lang.annotation.Annotation;
import java.util.function.Supplier;

/**
 * Test visitor used to verify Scala parity for {@link BeanElementBuilder}.
 */
public final class ScalaBeanElementBuilderVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * Bean builder behavior to enable for a compilation.
     */
    public enum Mode {
        ASSOCIATED_FACTORY,
        MULTIPLE_FACTORIES,
        EXECUTABLE_METHODS,
        AOP_ON_TYPE,
        AOP_ON_METHOD
    }

    private static final ThreadLocal<Mode> MODE = new ThreadLocal<>();

    /**
     * Executes a compilation with the given bean builder behavior enabled.
     *
     * @param mode The mode
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T withMode(Mode mode, Supplier<T> supplier) {
        MODE.set(mode);
        try {
            return supplier.get();
        } finally {
            MODE.remove();
        }
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        Mode mode = MODE.get();
        if (mode == null) {
            return;
        }
        switch (mode) {
            case ASSOCIATED_FACTORY -> addAssociatedFactory(element, context);
            case MULTIPLE_FACTORIES -> addMultipleFactories(element, context);
            case EXECUTABLE_METHODS -> addExecutableMethods(element, context);
            case AOP_ON_TYPE -> addAopBean(element, context, true);
            case AOP_ON_METHOD -> addAopBean(element, context, false);
            default -> {
            }
        }
    }

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    private static void addAssociatedFactory(ClassElement element, VisitorContext context) {
        if (!element.getSimpleName().equals("FactoryTrigger")) {
            return;
        }
        context.getClassElement(BeanProducer.class).ifPresent(producer -> {
            BeanElementBuilder builder = element.addAssociatedBean(producer);
            builder.produceBeans(ElementQuery.ALL_METHODS.onlyDeclared().named(name -> name.startsWith("build")));
            builder.produceBeans(ElementQuery.ALL_FIELDS.onlyDeclared().named(name -> name.equals("beanB")));
        });
    }

    private static void addMultipleFactories(ClassElement element, VisitorContext context) {
        if (!element.getSimpleName().equals("MultipleFactoryTrigger")) {
            return;
        }
        context.getClassElement(OtherBeanProducer.class).ifPresent(producer -> {
            ElementQuery<MethodElement> query = ElementQuery.ALL_METHODS.onlyDeclared().named(name -> name.equals("buildBeanA"));
            element.addAssociatedBean(producer)
                .qualifier(AnnotationValue.builder(Primary.class).build())
                .produceBeans(query, builder -> builder
                    .annotate("test.Foo")
                    .withParameters(parameters -> parameters[0].injectValue("primary"))
                    .qualifier(AnnotationValue.builder(Primary.class).build()));
            element.addAssociatedBean(producer)
                .qualifier("other")
                .produceBeans(query, builder -> builder
                    .annotate("test.Bar")
                    .withParameters(parameters -> parameters[0].injectValue("other"))
                    .qualifier("other"));
        });
    }

    private static void addExecutableMethods(ClassElement element, VisitorContext context) {
        if (!element.getSimpleName().equals("ScheduledTrigger")) {
            return;
        }
        context.getClassElement(ScheduledBean.class).ifPresent(scheduled -> element
            .addAssociatedBean(scheduled)
            .withMethods(ElementQuery.ALL_METHODS.onlyDeclared(), method -> method.executable(true)));
    }

    private static void addAopBean(ClassElement element, VisitorContext context, boolean interceptType) {
        if (!element.getSimpleName().equals("AopClient")) {
            return;
        }
        context.getClassElement(AopTarget.class).ifPresent(target -> {
            BeanElementBuilder builder = element.addAssociatedBean(target).inject();
            if (interceptType) {
                builder.intercept(mutationAnnotation());
            } else {
                builder.withMethods(
                    ElementQuery.ALL_METHODS.onlyDeclared().named(name -> name.equals("hello")),
                    method -> method.intercept(mutationAnnotation())
                );
            }
        });
    }

    private static AnnotationValue<Annotation> mutationAnnotation() {
        return AnnotationValue.builder("generatedaop.Mutating")
            .value("name")
            .build();
    }

    /**
     * Producer used by associated factory bean tests.
     */
    public static final class BeanProducer {

        /**
         * Field-produced bean.
         */
        @SuppressWarnings("VisibilityModifier")
        public final BeanB beanB = new BeanB("field");

        /**
         * Builds a method-produced bean.
         *
         * @return A method-produced bean
         */
        public BeanA buildBeanA() {
            return new BeanA("method");
        }

        /**
         * Builds a second method-produced bean.
         *
         * @return A second method-produced bean
         */
        public BeanC buildBeanC() {
            return new BeanC("method");
        }

        /**
         * Builds a method-produced interface bean.
         *
         * @return A method-produced interface bean
         */
        public InterfaceA buildInterfaceA() {
            return new DefaultInterfaceA();
        }
    }

    /**
     * Producer used by multiple factory bean tests.
     */
    public static final class OtherBeanProducer {

        /**
         * Builds a named bean.
         *
         * @param name The injected name
         * @return A named bean
         */
        public BeanA buildBeanA(String name) {
            return new BeanA(name);
        }
    }

    /**
     * Bean with generated executable method metadata.
     */
    public static final class ScheduledBean {

        /**
         * Returns a marker value.
         *
         * @return A marker value
         */
        public String scheduleMe() {
            return "good";
        }

        /**
         * Returns a marker value.
         *
         * @param one The first value
         * @return A marker value
         */
        public String scheduleOne(String one) {
            return "good " + one;
        }

        /**
         * Returns a marker value.
         *
         * @param one The first value
         * @param two The second value
         * @return A marker value
         */
        public String scheduleAnother(String one, String two) {
            return "good " + one + " " + two;
        }
    }

    /**
     * Bean used by generated AOP tests.
     */
    public static class AopTarget {

        /**
         * Returns a greeting.
         *
         * @param name The name
         * @return A greeting
         */
        public String hello(String name) {
            return "Hello " + name;
        }

        /**
         * Returns a greeting.
         *
         * @param name The name
         * @return A greeting
         */
        public String plain(String name) {
            return "Hello " + name;
        }
    }

    /**
     * Simple bean produced by builder tests.
     *
     * @param name The name
     */
    public record BeanA(String name) {
    }

    /**
     * Simple bean produced by builder tests.
     *
     * @param name The name
     */
    public record BeanB(String name) {
    }

    /**
     * Simple bean produced by builder tests.
     *
     * @param name The name
     */
    public record BeanC(String name) {
    }

    /**
     * Interface produced by builder tests.
     */
    public interface InterfaceA {

        /**
         * Returns the name.
         *
         * @return The name
         */
        String name();
    }

    private static final class DefaultInterfaceA implements InterfaceA {

        @Override
        public String name() {
            return "interface";
        }
    }
}
