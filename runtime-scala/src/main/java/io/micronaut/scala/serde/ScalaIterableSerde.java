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
import io.micronaut.core.type.Argument;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serializer;
import io.micronaut.serde.exceptions.SerdeException;
import io.micronaut.serde.util.CustomizableSerializer;
import jakarta.inject.Singleton;
import scala.collection.Iterable;
import scala.jdk.javaapi.CollectionConverters;


/**
 * Serialization for the Scala collections, which are a JSON array like any other.
 *
 * <p>They are not {@code java.util.Collection}s, so Micronaut Serialization has no serializer
 * for them and falls back to introspecting the concrete type -- {@code $colon$colon}, the cons
 * cell behind a {@code List}, which no one declared and which carries nothing meaningful. A
 * member declared {@code List[String]} therefore failed both ways round.</p>
 *
 * <p>Writing iterates, which every Scala collection supports, so one serializer naming the most
 * general Scala collection type covers all of them. Reading does not work that way and lives in
 * {@link ScalaCollectionDeserializer}.</p>
 *
 * @param <T> The element type
 */
@Singleton
@Requires(classes = Serializer.class)
public final class ScalaIterableSerde<T> implements CustomizableSerializer<Iterable<T>> {

    @Override
    public Serializer<Iterable<T>> createSpecific(EncoderContext context, Argument<? extends Iterable<T>> type)
        throws SerdeException {
        Argument<Object> elementType = elementType(type);
        Serializer<Object> elementSerializer = context.findSerializer(elementType)
            .createSpecific(context, elementType);
        return (encoder, encoderContext, iterableType, value) -> {
            Encoder array = encoder.encodeArray(iterableType);
            if (value != null) {
                java.util.Iterator<T> elements = CollectionConverters.asJava(value.iterator());
                while (elements.hasNext()) {
                    elementSerializer.serialize(array, encoderContext, elementType, elements.next());
                }
            }
            array.finishStructure();
        };
    }

    private static Argument<Object> elementType(Argument<?> type) {
        Argument<?>[] parameters = type.getTypeParameters();
        @SuppressWarnings("unchecked")
        Argument<Object> element = (Argument<Object>)
            (parameters.length == 1 ? parameters[0] : Argument.OBJECT_ARGUMENT);
        return element;
    }
}
