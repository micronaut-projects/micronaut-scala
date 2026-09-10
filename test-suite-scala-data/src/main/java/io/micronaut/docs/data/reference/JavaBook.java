package io.micronaut.docs.data.reference;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

@MappedEntity
public record JavaBook(@Id @GeneratedValue Long id, String title, int pages) {
}
