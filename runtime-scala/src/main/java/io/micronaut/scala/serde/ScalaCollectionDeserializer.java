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
package io.micronaut.scala.serde;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.serde.Decoder;
import io.micronaut.serde.Deserializer;
import io.micronaut.serde.Serializer;
import io.micronaut.serde.exceptions.SerdeException;
import io.micronaut.serde.util.CustomizableDeserializer;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;
import scala.collection.Iterable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reads a JSON array or object back into whichever Scala collection the member declares.
 *
 * <p>Writing and reading need different shapes of bean here, which is why this is separate from
 * {@link ScalaIterableSerde} and {@link ScalaMapSerde} rather than part of them. A serializer is
 * chosen by what it can accept, so one written for {@code scala.collection.Iterable} serves every
 * Scala collection; a deserializer is chosen by what it can <em>produce</em>, and one that
 * produces an {@code Iterable} cannot stand in where a {@code List} was asked for.</p>
 *
 * <p>The type parameter is what makes one bean answer for all of them. Its bound is published as
 * a type variable rather than a fixed type, and a type variable matches any subtype -- the device
 * Micronaut Serialization's own enum support uses, and the only alternative to naming every
 * collection anyone might declare.</p>
 *
 * <p>What the elements become is left to the conversion service, so the value produced is a
 * {@code List}, a {@code Seq}, a {@code Set} or a {@code Vector} according to the declaration
 * rather than one shape for all of them. Those converters are already registered for binding
 * configuration and injection points; this reuses them.</p>
 *
 * @param <C> The declared collection type
 */
@Singleton
@Requires(classes = Serializer.class)
public final class ScalaCollectionDeserializer<C extends Iterable<?>> implements CustomizableDeserializer<C> {

    private final ConversionService conversionService;

    /**
     * @param conversionService Converts the decoded elements to the declared collection type
     */
    public ScalaCollectionDeserializer(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @Override
    public Deserializer<C> createSpecific(DecoderContext context, Argument<? super C> type) throws SerdeException {
        // A Scala Map is an Iterable of pairs, so it arrives here too -- but a map is a JSON
        // object, not a list of two-element arrays, for the same reason java.util.Map is.
        boolean map = scala.collection.Map.class.isAssignableFrom(type.getType());
        Argument<Object> componentType = componentType(type, map);
        @SuppressWarnings("unchecked")
        Deserializer<Object> componentDeserializer = (Deserializer<Object>) context.findDeserializer(componentType)
            .createSpecific(context, componentType);
        return new Deserializer<>() {

            @Override
            public C deserialize(Decoder decoder, DecoderContext decoderContext, Argument<? super C> declared)
                throws IOException {
                return map
                    ? convert(decodeEntries(decoder, decoderContext, declared), declared)
                    : convert(decodeElements(decoder, decoderContext, declared), declared);
            }

            @Override
            public @Nullable C deserializeNullable(Decoder decoder, DecoderContext decoderContext,
                                                   Argument<? super C> declared) throws IOException {
                if (decoder.decodeNull()) {
                    return null;
                }
                return deserialize(decoder, decoderContext, declared);
            }

            private List<Object> decodeElements(Decoder decoder, DecoderContext decoderContext,
                                                Argument<? super C> declared) throws IOException {
                Decoder array = decoder.decodeArray(declared);
                var elements = new ArrayList<>();
                while (array.hasNextArrayValue()) {
                    elements.add(componentDeserializer.deserialize(array, decoderContext, componentType));
                }
                array.finishStructure();
                return elements;
            }

            private java.util.Map<String, Object> decodeEntries(Decoder decoder, DecoderContext decoderContext,
                                                                Argument<? super C> declared) throws IOException {
                Decoder object = decoder.decodeObject(declared);
                var entries = new LinkedHashMap<String, Object>();
                String key;
                while ((key = object.decodeKey()) != null) {
                    entries.put(key, componentDeserializer.deserialize(object, decoderContext, componentType));
                }
                object.finishStructure();
                return entries;
            }
        };
    }

    private C convert(Object decoded, Argument<?> declared) throws SerdeException {
        @SuppressWarnings("unchecked")
        Argument<C> target = (Argument<C>) declared;
        return conversionService.convert(decoded, ConversionContext.of(target))
            .orElseThrow(() -> new SerdeException(
                "Cannot convert the decoded value to [" + declared.getType().getName()
                    + "]. micronaut-runtime-scala registers converters for the Scala collections; "
                    + "a collection outside that set has to be reached through one that is in it."));
    }

    /**
     * The type of the thing decoded at each position: a map's value, or a collection's element.
     */
    private static Argument<Object> componentType(Argument<?> type, boolean map) {
        Argument<?>[] parameters = type.getTypeParameters();
        int index = map ? 1 : 0;
        @SuppressWarnings("unchecked")
        Argument<Object> component = (Argument<Object>)
            (parameters.length > index ? parameters[index] : Argument.OBJECT_ARGUMENT);
        return component;
    }
}
