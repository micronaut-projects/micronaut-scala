package io.micronaut.docs.data.reference;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.H2)
public interface JavaBookRepository extends CrudRepository<JavaBook, Long> {
    List<JavaBook> findByTitle(String title);
}
