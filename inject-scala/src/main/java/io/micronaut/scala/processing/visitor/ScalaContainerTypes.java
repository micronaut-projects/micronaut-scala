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

import io.micronaut.core.type.DefaultArgument;
import io.micronaut.inject.ast.ClassElement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What makes a Scala collection a container to Micronaut.
 *
 * <p>A bean whose type is a container contributes its elements as beans in their own right: a
 * factory method returning {@code java.util.List<Product>} produces {@code Product} beans. Two
 * separate things have to be true for that. The bean definition has to be written as a container,
 * which {@link ClassElement#isContainerType()} decides; and the element type has to be
 * discoverable, which {@code AbstractInitializableBeanDefinition.getContainerElement} reads as the
 * type argument of {@link Iterable}.</p>
 *
 * <p>Neither is true of a Scala collection by default. The names are not in
 * {@link DefaultArgument#CONTAINER_TYPES}, which is a fixed list of {@code java.util} types, and
 * {@code scala.collection.immutable.List} is not a {@link Iterable} -- Scala's collection
 * hierarchy does not extend Java's -- so walking its supertypes for that argument finds nothing
 * and the definition reports no element at all. The value side of the same gap is already
 * bridged at runtime, where the context converts a container that is not a {@code java.lang
 * .Iterable} rather than requiring one; this is the type side of it.</p>
 */
final class ScalaContainerTypes {

    private static final String SCALA_COLLECTION_PREFIX = "scala.collection.";
    private static final String ITERABLE = Iterable.class.getName();
    private static final String ITERABLE_PARAMETER = "T";

    private ScalaContainerTypes() {
    }

    /**
     * @param element The element
     * @return Whether beans of this type contribute their elements as beans
     */
    static boolean isContainerType(ClassElement element) {
        String name = element.getName();
        return DefaultArgument.CONTAINER_TYPES.contains(name) || name.startsWith(SCALA_COLLECTION_PREFIX);
    }

    /**
     * Whether a Scala collection stands in for the given type, which is only ever
     * {@link Iterable}.
     *
     * <p>Scala's collections do not implement {@code java.lang.Iterable}, so by the letter of the
     * type system the answer is no, and every framework that asks this question of a return type
     * or an injection point refuses the Scala form: Micronaut Data rejects
     * {@code def findByTitle(title: String): List[Book]} with "method returns an incompatible
     * type", leaving {@code java.util.List} as the only way to write a repository in Scala.</p>
     *
     * <p>Saying yes is what {@code micronaut-runtime-scala} makes true. Nothing casts the value:
     * the framework converts it, and the converters for the Scala collections are registered
     * exactly so it can. This is the same position already taken by reporting them as container
     * types, and it is what lets an application be written in Scala's own vocabulary rather than
     * in Java's.</p>
     *
     * @param element The element
     * @param type The name of the type being assigned to
     * @return Whether to treat the assignment as possible
     */
    static boolean isAssignableToIterable(ClassElement element, String type) {
        return Iterable.class.getName().equals(type)
            && element.getName().startsWith(SCALA_COLLECTION_PREFIX);
    }

    /**
     * States a Scala collection's element type as the type argument of {@link Iterable}, which is
     * where the container element is looked for.
     *
     * <p>Only a collection with exactly one type argument is described this way. A
     * {@code scala.collection.Map} has two and no single element type, and claiming one would
     * make its values beans.</p>
     *
     * @param element The element
     * @param allTypeArguments The type arguments resolved from the type hierarchy
     * @return The type arguments, with the {@link Iterable} entry added where it applies
     */
    static Map<String, Map<String, ClassElement>> withIterableTypeArguments(
        ClassElement element,
        Map<String, Map<String, ClassElement>> allTypeArguments) {
        if (allTypeArguments.containsKey(ITERABLE)
            || !element.getName().startsWith(SCALA_COLLECTION_PREFIX)) {
            return allTypeArguments;
        }
        Map<String, ClassElement> typeArguments = element.getTypeArguments();
        if (typeArguments.size() != 1) {
            return allTypeArguments;
        }
        ClassElement elementType = typeArguments.values().iterator().next();
        var withIterable = new LinkedHashMap<>(allTypeArguments);
        withIterable.put(ITERABLE, Map.of(ITERABLE_PARAMETER, elementType));
        return withIterable;
    }
}
