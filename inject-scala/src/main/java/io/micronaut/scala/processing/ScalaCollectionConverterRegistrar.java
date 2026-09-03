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
package io.micronaut.scala.processing;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.MutableConversionService;
import io.micronaut.core.convert.TypeConverterRegistrar;
import scala.collection.IterableOnce;
import scala.jdk.javaapi.CollectionConverters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Converters for Scala collection types.
 *
 * <p>Every converter into a Scala collection produces an independent copy rather than a view over
 * the Java collection it was given. Micronaut hands these converters a container-owned collection,
 * and a view over it would let a later mutation change the value already injected into a bean. The
 * mutable targets copy into a mutable Scala collection, so the bean still owns something it can
 * modify; the mutation simply does not travel back. The two Scala-to-Java converters are the
 * deliberate exception: there the caller owns the source, and wrapping is the cheaper, expected
 * behaviour of {@code CollectionConverters.asJavaCollection}.</p>
 */
@Internal
public final class ScalaCollectionConverterRegistrar implements TypeConverterRegistrar {

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void register(MutableConversionService conversionService) {
        conversionService.addConverter(Collection.class, scala.collection.Iterable.class, ScalaCollectionConverterRegistrar::toScalaIterable);
        conversionService.addConverter(Collection.class, scala.collection.Seq.class, ScalaCollectionConverterRegistrar::toScalaSeq);
        conversionService.addConverter(Collection.class, scala.collection.Set.class, ScalaCollectionConverterRegistrar::toScalaSet);
        conversionService.addConverter(Collection.class, scala.collection.IndexedSeq.class, ScalaCollectionConverterRegistrar::toScalaIndexedSeq);
        conversionService.addConverter(Collection.class, scala.collection.mutable.Iterable.class, ScalaCollectionConverterRegistrar::toMutableIterable);
        conversionService.addConverter(Collection.class, scala.collection.mutable.Seq.class, ScalaCollectionConverterRegistrar::toMutableSeq);
        conversionService.addConverter(Collection.class, scala.collection.mutable.Set.class, ScalaCollectionConverterRegistrar::toMutableSet);
        conversionService.addConverter(Collection.class, scala.collection.mutable.Buffer.class, ScalaCollectionConverterRegistrar::toMutableBuffer);
        conversionService.addConverter(Collection.class, scala.collection.immutable.Iterable.class, ScalaCollectionConverterRegistrar::toImmutableIterable);
        conversionService.addConverter(Collection.class, scala.collection.immutable.Seq.class, ScalaCollectionConverterRegistrar::toImmutableSeq);
        conversionService.addConverter(Collection.class, scala.collection.immutable.Set.class, ScalaCollectionConverterRegistrar::toImmutableSet);
        conversionService.addConverter(Collection.class, scala.collection.immutable.IndexedSeq.class, ScalaCollectionConverterRegistrar::toImmutableIndexedSeq);
        conversionService.addConverter(Collection.class, scala.collection.immutable.List.class, ScalaCollectionConverterRegistrar::toImmutableList);
        conversionService.addConverter(Collection.class, scala.collection.immutable.Vector.class, ScalaCollectionConverterRegistrar::toImmutableVector);

        conversionService.addConverter(scala.collection.Iterable.class, Iterable.class, ScalaCollectionConverterRegistrar::toJavaIterable);
        conversionService.addConverter(scala.collection.Iterable.class, Collection.class, ScalaCollectionConverterRegistrar::toJavaCollection);

        conversionService.addConverter(Map.class, scala.collection.Map.class, ScalaCollectionConverterRegistrar::toScalaMap);
        conversionService.addConverter(Map.class, scala.collection.mutable.Map.class, ScalaCollectionConverterRegistrar::toMutableMap);
        conversionService.addConverter(Map.class, scala.collection.immutable.Map.class, ScalaCollectionConverterRegistrar::toImmutableMap);
        conversionService.addConverter(Optional.class, scala.Option.class, ScalaCollectionConverterRegistrar::toScalaOption);
    }

    private static Optional<scala.collection.Iterable> toScalaIterable(Collection<?> collection,
                                                                       Class<scala.collection.Iterable> targetType,
                                                                       ConversionContext context) {
        return Optional.of(scala.collection.immutable.Iterable.from(toScalaIterableOnce(collection)));
    }

    private static Optional<scala.collection.Seq> toScalaSeq(Collection<?> collection,
                                                             Class<scala.collection.Seq> targetType,
                                                             ConversionContext context) {
        return Optional.of(scala.collection.immutable.Seq.from(toScalaIterableOnce(collection)));
    }

    private static Optional<scala.collection.Set> toScalaSet(Collection<?> collection,
                                                             Class<scala.collection.Set> targetType,
                                                             ConversionContext context) {
        return Optional.of(scala.collection.immutable.Set.from(toScalaIterableOnce(collection)));
    }

    private static Optional<scala.collection.IndexedSeq> toScalaIndexedSeq(Collection<?> collection,
                                                                           Class<scala.collection.IndexedSeq> targetType,
                                                                           ConversionContext context) {
        return Optional.of(scala.collection.immutable.Vector.from(toScalaIterableOnce(collection)));
    }

    private static Optional<scala.collection.immutable.Iterable> toImmutableIterable(Collection<?> collection,
                                                                                    Class<scala.collection.immutable.Iterable> targetType,
                                                                                    ConversionContext context) {
        return Optional.of(scala.collection.immutable.Iterable.from(toScalaIterableOnce(collection)));
    }

    private static Optional<scala.collection.immutable.Seq> toImmutableSeq(Collection<?> collection,
                                                                           Class<scala.collection.immutable.Seq> targetType,
                                                                           ConversionContext context) {
        return Optional.of(scala.collection.immutable.Seq.from(toScalaIterableOnce(collection)));
    }

    private static Optional<scala.collection.immutable.Set> toImmutableSet(Collection<?> collection,
                                                                           Class<scala.collection.immutable.Set> targetType,
                                                                           ConversionContext context) {
        return Optional.of(scala.collection.immutable.Set.from(toScalaIterableOnce(collection)));
    }

    private static Optional<scala.collection.immutable.IndexedSeq> toImmutableIndexedSeq(Collection<?> collection,
                                                                                         Class<scala.collection.immutable.IndexedSeq> targetType,
                                                                                         ConversionContext context) {
        return Optional.of(scala.collection.immutable.Vector.from(toScalaIterableOnce(collection)));
    }

    private static Optional<scala.collection.mutable.Iterable> toMutableIterable(Collection<?> collection,
                                                                                 Class<scala.collection.mutable.Iterable> targetType,
                                                                                 ConversionContext context) {
        return Optional.of(asMutableBuffer(collection));
    }

    private static Optional<scala.collection.mutable.Seq> toMutableSeq(Collection<?> collection,
                                                                       Class<scala.collection.mutable.Seq> targetType,
                                                                       ConversionContext context) {
        return Optional.of(asMutableBuffer(collection));
    }

    @SuppressWarnings("unchecked")
    private static Optional<scala.collection.mutable.Set> toMutableSet(Collection<?> collection,
                                                                       Class<scala.collection.mutable.Set> targetType,
                                                                       ConversionContext context) {
        return Optional.of((scala.collection.mutable.Set) scala.collection.mutable.Set.from(toScalaIterableOnce(collection)));
    }

    private static Optional<scala.collection.mutable.Buffer> toMutableBuffer(Collection<?> collection,
                                                                             Class<scala.collection.mutable.Buffer> targetType,
                                                                             ConversionContext context) {
        return Optional.of(asMutableBuffer(collection));
    }

    private static Optional<scala.collection.immutable.List> toImmutableList(Collection<?> collection,
                                                                             Class<scala.collection.immutable.List> targetType,
                                                                             ConversionContext context) {
        return Optional.of(scala.collection.immutable.List.from(toScalaIterableOnce(collection)));
    }

    private static Optional<scala.collection.immutable.Vector> toImmutableVector(Collection<?> collection,
                                                                                 Class<scala.collection.immutable.Vector> targetType,
                                                                                 ConversionContext context) {
        return Optional.of(scala.collection.immutable.Vector.from(toScalaIterableOnce(collection)));
    }

    private static Optional<Iterable> toJavaIterable(scala.collection.Iterable<?> collection,
                                                     Class<Iterable> targetType,
                                                     ConversionContext context) {
        return Optional.of(CollectionConverters.asJavaCollection(collection));
    }

    private static Optional<Collection> toJavaCollection(scala.collection.Iterable<?> collection,
                                                         Class<Collection> targetType,
                                                         ConversionContext context) {
        return Optional.of(CollectionConverters.asJavaCollection(collection));
    }

    private static Optional<scala.collection.Map> toScalaMap(Map<?, ?> map,
                                                             Class<scala.collection.Map> targetType,
                                                             ConversionContext context) {
        return Optional.of(scala.collection.immutable.Map.from(CollectionConverters.asScala(map)));
    }

    @SuppressWarnings("unchecked")
    private static Optional<scala.collection.mutable.Map> toMutableMap(Map<?, ?> map,
                                                                       Class<scala.collection.mutable.Map> targetType,
                                                                       ConversionContext context) {
        return Optional.of((scala.collection.mutable.Map) scala.collection.mutable.Map.from(CollectionConverters.asScala(map)));
    }

    private static Optional<scala.collection.immutable.Map> toImmutableMap(Map<?, ?> map,
                                                                           Class<scala.collection.immutable.Map> targetType,
                                                                           ConversionContext context) {
        return Optional.of(scala.collection.immutable.Map.from(CollectionConverters.asScala(map)));
    }

    private static Optional<scala.Option> toScalaOption(Optional<?> optional,
                                                        Class<scala.Option> targetType,
                                                        ConversionContext context) {
        return Optional.of(scala.Option.apply(optional.orElse(null)));
    }

    private static IterableOnce<?> toScalaIterableOnce(Collection<?> collection) {
        return CollectionConverters.asScala(collection);
    }

    private static scala.collection.mutable.Buffer<?> asMutableBuffer(Collection<?> collection) {
        return CollectionConverters.asScala(new ArrayList<>(collection));
    }
}
