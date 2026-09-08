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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.type.Argument;
import io.micronaut.core.convert.MutableConversionService;
import io.micronaut.core.convert.TypeConverterRegistrar;
import scala.collection.IterableOnce;
import scala.jdk.javaapi.CollectionConverters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Set by {@link #register}; the converters below are only reachable after that. */
    private @Nullable MutableConversionService conversionService;

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void register(MutableConversionService conversionService) {
        this.conversionService = conversionService;
        conversionService.addConverter(Collection.class, scala.collection.Iterable.class, this::toScalaIterable);
        conversionService.addConverter(Collection.class, scala.collection.Seq.class, this::toScalaSeq);
        conversionService.addConverter(Collection.class, scala.collection.Set.class, this::toScalaSet);
        conversionService.addConverter(Collection.class, scala.collection.IndexedSeq.class, this::toScalaIndexedSeq);
        conversionService.addConverter(Collection.class, scala.collection.mutable.Iterable.class, this::toMutableIterable);
        conversionService.addConverter(Collection.class, scala.collection.mutable.Seq.class, this::toMutableSeq);
        conversionService.addConverter(Collection.class, scala.collection.mutable.Set.class, this::toMutableSet);
        conversionService.addConverter(Collection.class, scala.collection.mutable.Buffer.class, this::toMutableBuffer);
        conversionService.addConverter(Collection.class, scala.collection.immutable.Iterable.class, this::toImmutableIterable);
        conversionService.addConverter(Collection.class, scala.collection.immutable.Seq.class, this::toImmutableSeq);
        conversionService.addConverter(Collection.class, scala.collection.immutable.Set.class, this::toImmutableSet);
        conversionService.addConverter(Collection.class, scala.collection.immutable.IndexedSeq.class, this::toImmutableIndexedSeq);
        conversionService.addConverter(Collection.class, scala.collection.immutable.List.class, this::toImmutableList);
        conversionService.addConverter(Collection.class, scala.collection.immutable.Vector.class, this::toImmutableVector);

        conversionService.addConverter(scala.collection.Iterable.class, Iterable.class, this::toJavaIterable);
        conversionService.addConverter(scala.collection.Iterable.class, Collection.class, this::toJavaCollection);

        conversionService.addConverter(Map.class, scala.collection.Map.class, this::toScalaMap);
        conversionService.addConverter(Map.class, scala.collection.mutable.Map.class, this::toMutableMap);
        conversionService.addConverter(Map.class, scala.collection.immutable.Map.class, this::toImmutableMap);
        conversionService.addConverter(Optional.class, scala.Option.class, this::toScalaOption);
    }

    private Optional<scala.collection.Iterable> toScalaIterable(Collection<?> collection,
                                                                       Class<scala.collection.Iterable> targetType,
                                                                       ConversionContext context) {
        return toScalaIterableOnce(collection, context).map(elements -> scala.collection.immutable.Iterable.from(elements));
    }

    private Optional<scala.collection.Seq> toScalaSeq(Collection<?> collection,
                                                             Class<scala.collection.Seq> targetType,
                                                             ConversionContext context) {
        return toScalaIterableOnce(collection, context).map(elements -> scala.collection.immutable.Seq.from(elements));
    }

    private Optional<scala.collection.Set> toScalaSet(Collection<?> collection,
                                                             Class<scala.collection.Set> targetType,
                                                             ConversionContext context) {
        return toScalaIterableOnce(collection, context).map(elements -> scala.collection.immutable.Set.from(elements));
    }

    private Optional<scala.collection.IndexedSeq> toScalaIndexedSeq(Collection<?> collection,
                                                                           Class<scala.collection.IndexedSeq> targetType,
                                                                           ConversionContext context) {
        return toScalaIterableOnce(collection, context).map(elements -> scala.collection.immutable.Vector.from(elements));
    }

    private Optional<scala.collection.immutable.Iterable> toImmutableIterable(Collection<?> collection,
                                                                                    Class<scala.collection.immutable.Iterable> targetType,
                                                                                    ConversionContext context) {
        return toScalaIterableOnce(collection, context).map(elements -> scala.collection.immutable.Iterable.from(elements));
    }

    private Optional<scala.collection.immutable.Seq> toImmutableSeq(Collection<?> collection,
                                                                           Class<scala.collection.immutable.Seq> targetType,
                                                                           ConversionContext context) {
        return toScalaIterableOnce(collection, context).map(elements -> scala.collection.immutable.Seq.from(elements));
    }

    private Optional<scala.collection.immutable.Set> toImmutableSet(Collection<?> collection,
                                                                           Class<scala.collection.immutable.Set> targetType,
                                                                           ConversionContext context) {
        return toScalaIterableOnce(collection, context).map(elements -> scala.collection.immutable.Set.from(elements));
    }

    private Optional<scala.collection.immutable.IndexedSeq> toImmutableIndexedSeq(Collection<?> collection,
                                                                                         Class<scala.collection.immutable.IndexedSeq> targetType,
                                                                                         ConversionContext context) {
        return toScalaIterableOnce(collection, context).map(elements -> scala.collection.immutable.Vector.from(elements));
    }

    private Optional<scala.collection.mutable.Iterable> toMutableIterable(Collection<?> collection,
                                                                                 Class<scala.collection.mutable.Iterable> targetType,
                                                                                 ConversionContext context) {
        return asMutableBuffer(collection, context).map(buffer -> buffer);
    }

    private Optional<scala.collection.mutable.Seq> toMutableSeq(Collection<?> collection,
                                                                       Class<scala.collection.mutable.Seq> targetType,
                                                                       ConversionContext context) {
        return asMutableBuffer(collection, context).map(buffer -> buffer);
    }

    @SuppressWarnings("unchecked")
    private Optional<scala.collection.mutable.Set> toMutableSet(Collection<?> collection,
                                                                       Class<scala.collection.mutable.Set> targetType,
                                                                       ConversionContext context) {
        return toScalaIterableOnce(collection, context).map(elements -> (scala.collection.mutable.Set) scala.collection.mutable.Set.from(elements));
    }

    private Optional<scala.collection.mutable.Buffer> toMutableBuffer(Collection<?> collection,
                                                                             Class<scala.collection.mutable.Buffer> targetType,
                                                                             ConversionContext context) {
        return asMutableBuffer(collection, context).map(buffer -> buffer);
    }

    private Optional<scala.collection.immutable.List> toImmutableList(Collection<?> collection,
                                                                             Class<scala.collection.immutable.List> targetType,
                                                                             ConversionContext context) {
        return toScalaIterableOnce(collection, context).map(elements -> scala.collection.immutable.List.from(elements));
    }

    private Optional<scala.collection.immutable.Vector> toImmutableVector(Collection<?> collection,
                                                                                 Class<scala.collection.immutable.Vector> targetType,
                                                                                 ConversionContext context) {
        return toScalaIterableOnce(collection, context).map(elements -> scala.collection.immutable.Vector.from(elements));
    }

    private Optional<Iterable> toJavaIterable(scala.collection.Iterable<?> collection,
                                                     Class<Iterable> targetType,
                                                     ConversionContext context) {
        return Optional.of(CollectionConverters.asJavaCollection(collection));
    }

    private Optional<Collection> toJavaCollection(scala.collection.Iterable<?> collection,
                                                         Class<Collection> targetType,
                                                         ConversionContext context) {
        return Optional.of(CollectionConverters.asJavaCollection(collection));
    }

    private Optional<scala.collection.Map> toScalaMap(Map<?, ?> map,
                                                             Class<scala.collection.Map> targetType,
                                                             ConversionContext context) {
        return convertEntries(map, context).map(entries -> scala.collection.immutable.Map.from(CollectionConverters.asScala(entries)));
    }

    @SuppressWarnings("unchecked")
    private Optional<scala.collection.mutable.Map> toMutableMap(Map<?, ?> map,
                                                                       Class<scala.collection.mutable.Map> targetType,
                                                                       ConversionContext context) {
        return convertEntries(map, context).map(entries -> (scala.collection.mutable.Map) scala.collection.mutable.Map.from(CollectionConverters.asScala(entries)));
    }

    private Optional<scala.collection.immutable.Map> toImmutableMap(Map<?, ?> map,
                                                                           Class<scala.collection.immutable.Map> targetType,
                                                                           ConversionContext context) {
        return convertEntries(map, context).map(entries -> scala.collection.immutable.Map.from(CollectionConverters.asScala(entries)));
    }

    private Optional<scala.Option> toScalaOption(Optional<?> optional,
                                                        Class<scala.Option> targetType,
                                                        ConversionContext context) {
        Object value = optional.orElse(null);
        if (value == null) {
            return Optional.of(scala.Option.empty());
        }
        return convertEntryPart(value, context.getFirstTypeVariable().orElse(null), context)
            .map(converted -> scala.Option.apply(converted));
    }

    /**
     * The source elements converted to the target's element type.
     *
     * <p>These converters previously ignored the {@link ConversionContext} entirely, so a
     * {@code List[Int]} bound from configuration held {@code String}s and reported itself as
     * a {@code List[Int]}. The mismatch surfaced as a {@code ClassCastException} the first
     * time an element was used, rather than as a conversion error at binding time.
     *
     * @return empty when an element cannot be converted, so the whole conversion fails
     */
    private Optional<IterableOnce<?>> toScalaIterableOnce(Collection<?> collection, ConversionContext context) {
        return convertElements(collection, context).map(elements -> CollectionConverters.asScala((Collection<Object>) elements));
    }

    private MutableConversionService conversionService() {
        return Objects.requireNonNull(conversionService, "register(MutableConversionService) has not been called");
    }

    private Optional<List<Object>> convertElements(Collection<?> collection, ConversionContext context) {
        Argument<?> elementType = context.getFirstTypeVariable().orElse(null);
        if (elementType == null || elementType.getType() == Object.class) {
            return Optional.of(new ArrayList<>(collection));
        }
        List<Object> converted = new ArrayList<>(collection.size());
        for (Object element : collection) {
            Optional<?> convertedElement = conversionService().convert(element, elementType);
            if (convertedElement.isEmpty()) {
                context.reject(element, new ConversionErrorException(elementType,
                    new IllegalArgumentException("Cannot convert element [" + element + "] to " + elementType.getType().getName())));
                return Optional.empty();
            }
            converted.add(convertedElement.get());
        }
        return Optional.of(converted);
    }

    private Optional<Map<Object, Object>> convertEntries(Map<?, ?> map, ConversionContext context) {
        Argument<?>[] typeParameters = context.getTypeParameters();
        Argument<?> keyType = typeParameters.length > 0 ? typeParameters[0] : null;
        Argument<?> valueType = typeParameters.length > 1 ? typeParameters[1] : null;
        Map<Object, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Optional<?> key = convertEntryPart(entry.getKey(), keyType, context);
            Optional<?> value = convertEntryPart(entry.getValue(), valueType, context);
            if (key.isEmpty() || value.isEmpty()) {
                return Optional.empty();
            }
            converted.put(key.get(), value.get());
        }
        return Optional.of(converted);
    }

    private Optional<?> convertEntryPart(Object value, @Nullable Argument<?> type, ConversionContext context) {
        if (type == null || type.getType() == Object.class || value == null) {
            return Optional.ofNullable(value);
        }
        Optional<?> converted = conversionService().convert(value, type);
        if (converted.isEmpty()) {
            context.reject(value, new ConversionErrorException(type,
                new IllegalArgumentException("Cannot convert [" + value + "] to " + type.getType().getName())));
        }
        return converted;
    }

    @SuppressWarnings("unchecked")
    private Optional<scala.collection.mutable.Buffer<?>> asMutableBuffer(Collection<?> collection, ConversionContext context) {
        return convertElements(collection, context)
            .map(elements -> (scala.collection.mutable.Buffer<?>) CollectionConverters.asScala(new ArrayList<>(elements)));
    }
}
