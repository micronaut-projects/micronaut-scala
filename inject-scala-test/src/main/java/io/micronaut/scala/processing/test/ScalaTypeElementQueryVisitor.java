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

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.EnumConstantElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.TypeElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Test visitor used to assert Scala support for {@link TypeElementQuery}.
 */
public final class ScalaTypeElementQueryVisitor implements TypeElementVisitor<Object, Object> {

    @SuppressWarnings("VisibilityModifier")
    public static boolean enabled;
    @SuppressWarnings({"VisibilityModifier", "ConstantName"})
    public static final List<ClassElement> visitedClasses = new ArrayList<>();
    @SuppressWarnings({"VisibilityModifier", "ConstantName"})
    public static final List<ConstructorElement> visitedConstructors = new ArrayList<>();
    @SuppressWarnings({"VisibilityModifier", "ConstantName"})
    public static final List<FieldElement> visitedFields = new ArrayList<>();
    @SuppressWarnings({"VisibilityModifier", "ConstantName"})
    public static final List<EnumConstantElement> visitedEnumConstants = new ArrayList<>();
    @SuppressWarnings({"VisibilityModifier", "ConstantName"})
    public static final List<MethodElement> visitedMethods = new ArrayList<>();
    @SuppressWarnings("VisibilityModifier")
    public static TypeElementQuery query = TypeElementQuery.DEFAULT;

    /**
     * Clears all captured state.
     */
    public static void cleanup() {
        enabled = false;
        query = TypeElementQuery.DEFAULT;
        visitedClasses.clear();
        visitedConstructors.clear();
        visitedFields.clear();
        visitedEnumConstants.clear();
        visitedMethods.clear();
    }

    @Override
    public TypeElementQuery query() {
        return query;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (enabled) {
            visitedClasses.add(element);
        }
    }

    @Override
    public void visitConstructor(ConstructorElement element, VisitorContext context) {
        if (enabled) {
            visitedConstructors.add(element);
        }
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        if (enabled) {
            visitedFields.add(element);
        }
    }

    @Override
    public void visitEnumConstant(EnumConstantElement element, VisitorContext context) {
        if (enabled) {
            visitedEnumConstants.add(element);
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (enabled) {
            visitedMethods.add(element);
        }
    }
}
