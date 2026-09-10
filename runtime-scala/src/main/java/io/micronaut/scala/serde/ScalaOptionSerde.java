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
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serializer;
import io.micronaut.serde.exceptions.SerdeException;
import io.micronaut.serde.util.CustomizableDeserializer;
import io.micronaut.serde.util.CustomizableSerializer;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;
import scala.Option;

import java.io.IOException;

/**
 * Serialization for {@link Option}, which is how Scala says a value may be absent.
 *
 * <p>Serialization has no notion of an option: a member is present or it is not. Micronaut
 * Serialization already says that for {@code java.util.Optional}, and this says the same for
 * Scala's. Without it {@code Some} and {@code None} are introspected as though they were beans
 * of their own, which fails -- they carry no properties anyone declared.</p>
 *
 * <p>An empty option is reported absent as well as null, so a member declared {@code
 * Option[String]} is left out of the document entirely where the configuration omits nulls,
 * which is what makes the type worth using rather than merely tolerated.</p>
 *
 * @param <T> The value type
 */
@Singleton
@Requires(classes = Serializer.class)
public final class ScalaOptionSerde<T> implements
    CustomizableSerializer<Option<T>>,
    CustomizableDeserializer<Option<T>> {

    @Override
    public Serializer<Option<T>> createSpecific(EncoderContext context, Argument<? extends Option<T>> type)
        throws SerdeException {
        Argument<?> valueType = valueType(type);
        Serializer<? super Object> valueSerializer = componentSerializer(context, valueType);
        return new Serializer<>() {

            @Override
            public void serialize(Encoder encoder, EncoderContext encoderContext,
                                  Argument<? extends Option<T>> optionType, Option<T> value) throws IOException {
                if (value == null || value.isEmpty()) {
                    encoder.encodeNull();
                    return;
                }
                @SuppressWarnings("unchecked")
                Argument<Object> asObject = (Argument<Object>) valueType;
                valueSerializer.serialize(encoder, encoderContext, asObject, value.get());
            }

            @Override
            public boolean isEmpty(EncoderContext encoderContext, @Nullable Option<T> value) {
                return value == null || value.isEmpty();
            }

            @Override
            public boolean isAbsent(EncoderContext encoderContext, @Nullable Option<T> value) {
                return value == null || value.isEmpty();
            }
        };
    }

    @Override
    public Deserializer<Option<T>> createSpecific(DecoderContext context, Argument<? super Option<T>> type)
        throws SerdeException {
        Argument<?> valueType = valueType(type);
        @SuppressWarnings("unchecked")
        Argument<Object> valueArgument = (Argument<Object>) valueType;
        Deserializer<?> valueDeserializer = context.findDeserializer(valueArgument)
            .createSpecific(context, valueArgument);
        return new Deserializer<>() {

            @Override
            public Option<T> deserialize(Decoder decoder, DecoderContext decoderContext,
                                         Argument<? super Option<T>> optionType) throws IOException {
                if (decoder.decodeNull()) {
                    return Option.empty();
                }
                @SuppressWarnings("unchecked")
                Argument<Object> asObject = (Argument<Object>) valueType;
                @SuppressWarnings("unchecked")
                Deserializer<Object> asObjectDeserializer = (Deserializer<Object>) valueDeserializer;
                @SuppressWarnings("unchecked")
                T decoded = (T) asObjectDeserializer.deserialize(decoder, decoderContext, asObject);
                return Option.apply(decoded);
            }

            @Override
            public Option<T> deserializeNullable(Decoder decoder, DecoderContext decoderContext,
                                                 Argument<? super Option<T>> optionType) throws IOException {
                // The interface's own implementation answers null for a null document value,
                // without consulting the deserializer. An option is the one type for which that
                // is the wrong answer: a JSON null is exactly what `None` means, and answering
                // null instead puts a null where the Scala type says there cannot be one.
                return deserialize(decoder, decoderContext, optionType);
            }

            @Override
            public Option<T> getDefaultValue(DecoderContext decoderContext, Argument<? super Option<T>> optionType) {
                // A member the document omits is `None` rather than null, which is the whole
                // point of declaring it as an option.
                return Option.empty();
            }
        };
    }

    @Override
    public Option<T> getDefaultValue(DecoderContext context, Argument<? super Option<T>> type) {
        // A member the document omits is `None`, not null. Asked of the bean rather than of the
        // specific deserializer, because that is where a missing property is resolved.
        //
        // Neither is consulted today: serialization decides whether to ask for a default from a
        // fixed list of types that a Deserializer cannot add itself to, so an option with no
        // default of its own still reads as null. Written as the answer this type has whenever
        // that is asked for -- micronaut-serialization#1416.
        return Option.empty();
    }

    private static Argument<?> valueType(Argument<?> type) {
        Argument<?>[] parameters = type.getTypeParameters();
        return parameters.length == 1 ? parameters[0] : Argument.OBJECT_ARGUMENT;
    }

    private static Serializer<? super Object> componentSerializer(EncoderContext context, Argument<?> valueType)
        throws SerdeException {
        @SuppressWarnings("unchecked")
        Argument<Object> asObject = (Argument<Object>) valueType;
        return context.findSerializer(asObject).createSpecific(context, asObject);
    }
}
