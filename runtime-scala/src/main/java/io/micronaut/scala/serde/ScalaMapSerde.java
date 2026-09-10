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
import scala.Tuple2;
import scala.collection.Map;
import scala.jdk.javaapi.CollectionConverters;


/**
 * Serialization for a Scala {@code Map}, which is a JSON object rather than a list of pairs.
 *
 * <p>A Scala map is an iterable of {@code Tuple2}, so the serializer for the collections would
 * take it as one and write an array of tuples -- and then fail, because a tuple has no
 * introspection either. It needs its own handling for the same reason {@code java.util.Map} does:
 * the keys become the field names.</p>
 *
 * <p>Reading a map back is {@link ScalaCollectionDeserializer}'s job, for the reason given
 * there.</p>
 *
 * @param <V> The value type
 */
@Singleton
@Requires(classes = Serializer.class)
public final class ScalaMapSerde<V> implements CustomizableSerializer<Map<String, V>> {

    @Override
    public Serializer<Map<String, V>> createSpecific(EncoderContext context,
                                                     Argument<? extends Map<String, V>> type)
        throws SerdeException {
        Argument<Object> valueType = valueType(type);
        Serializer<Object> valueSerializer = context.findSerializer(valueType)
            .createSpecific(context, valueType);
        return (encoder, encoderContext, mapType, value) -> {
            Encoder object = encoder.encodeObject(mapType);
            if (value != null) {
                java.util.Iterator<Tuple2<String, V>> entries = CollectionConverters.asJava(value.iterator());
                while (entries.hasNext()) {
                    Tuple2<String, V> entry = entries.next();
                    object.encodeKey(String.valueOf(entry._1()));
                    valueSerializer.serialize(object, encoderContext, valueType, entry._2());
                }
            }
            object.finishStructure();
        };
    }

    private static Argument<Object> valueType(Argument<?> type) {
        Argument<?>[] parameters = type.getTypeParameters();
        @SuppressWarnings("unchecked")
        Argument<Object> value = (Argument<Object>)
            (parameters.length == 2 ? parameters[1] : Argument.OBJECT_ARGUMENT);
        return value;
    }
}
