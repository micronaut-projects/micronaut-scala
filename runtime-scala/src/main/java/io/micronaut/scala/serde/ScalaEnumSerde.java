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
import io.micronaut.serde.Decoder;
import io.micronaut.serde.Deserializer;
import io.micronaut.serde.Serializer;
import io.micronaut.serde.exceptions.SerdeException;
import io.micronaut.serde.util.CustomizableDeserializer;
import io.micronaut.serde.util.CustomizableSerializer;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Serialization for a Scala 3 {@code enum}, which is a name in a document as a Java enum is.
 *
 * <p>A Scala 3 enum is not a {@code java.lang.Enum} unless it says so, so the serializer Micronaut
 * Serialization has for enums does not apply and the case is introspected as a bean instead. The
 * case objects have no properties, so nothing useful comes out and nothing at all goes back
 * in.</p>
 *
 * <p>The name written is the one the case declares. Reading it back goes through the companion's
 * generated {@code valueOf}, which is the same lookup the language itself uses, so a name the
 * enum does not have is rejected here rather than becoming a null the caller meets later.</p>
 *
 * <p>The type parameter is what makes one bean answer for every enum. A serializer is chosen by
 * what it can accept, so naming {@code scala.reflect.Enum} outright would do for writing; a
 * deserializer is chosen by what it can produce, and one that produces {@code scala.reflect.Enum}
 * cannot stand in where a particular enum was asked for. Declaring the bound as a type variable
 * instead publishes the bean under a type variable rather than a fixed type, which matches any
 * subtype -- the same device Micronaut Serialization's own enum support uses.</p>
 *
 * @param <E> The enum type
 */
@Singleton
@Requires(classes = Serializer.class)
public final class ScalaEnumSerde<E extends scala.reflect.Enum> implements
    CustomizableSerializer<E>,
    CustomizableDeserializer<E> {

    @Override
    public Serializer<E> createSpecific(EncoderContext context, Argument<? extends E> type) {
        return (encoder, encoderContext, enumType, value) -> {
            if (value == null) {
                encoder.encodeNull();
            } else {
                encoder.encodeString(value.toString());
            }
        };
    }

    @Override
    public Deserializer<E> createSpecific(DecoderContext context, Argument<? super E> type)
        throws SerdeException {
        Method valueOf = valueOf(type.getType());
        return new Deserializer<>() {

            @Override
            @SuppressWarnings("unchecked")
            public E deserialize(Decoder decoder, DecoderContext decoderContext,
                                 Argument<? super E> enumType) throws IOException {
                String name = decoder.decodeString();
                try {
                    return (E) valueOf.invoke(null, name);
                } catch (ReflectiveOperationException | IllegalArgumentException e) {
                    throw new SerdeException(
                        "Cannot deserialize [" + name + "] as [" + enumType.getType().getName() + "]", e);
                }
            }

            @Override
            public @Nullable E deserializeNullable(Decoder decoder, DecoderContext decoderContext,
                                                   Argument<? super E> enumType) throws IOException {
                if (decoder.decodeNull()) {
                    return null;
                }
                return deserialize(decoder, decoderContext, enumType);
            }
        };
    }

    /**
     * The {@code valueOf} the compiler generates on a Scala 3 enum's companion, exposed as a
     * static method on the enum class itself.
     */
    private static Method valueOf(Class<?> enumType) throws SerdeException {
        try {
            return enumType.getMethod("valueOf", String.class);
        } catch (NoSuchMethodException e) {
            throw new SerdeException(
                "[" + enumType.getName() + "] has no valueOf(String), so a name cannot be read back into it. "
                    + "Only an enum whose cases take no parameters has one.", e);
        }
    }
}
