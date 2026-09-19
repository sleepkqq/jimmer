package io.quarkiverse.jimmer.it.resource;

import java.math.BigDecimal;

import jakarta.inject.Inject;

import org.babyfish.jimmer.sql.ast.mutation.SaveMode;
import org.babyfish.jimmer.sql.exception.SaveException;
import org.babyfish.jimmer.meta.ImmutableType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkiverse.jimmer.it.entity.Book;
import io.quarkiverse.jimmer.it.entity.BookStore;
import io.quarkiverse.jimmer.it.entity.Alias;
import io.quarkiverse.jimmer.it.entity.ConsumerAlias;
import io.quarkiverse.jimmer.it.entity.Immutables;
import io.quarkiverse.jimmer.it.entity.TeamAlias;
import io.quarkiverse.jimmer.it.repository.BookRepository;
import io.quarkiverse.jimmer.it.repository.BookStoreRepository;
import io.quarkiverse.jimmer.it.repository.ConsumerAliasRepository;
import io.quarkiverse.jimmer.it.repository.TeamAliasRepository;
import io.quarkiverse.jimmer.runtime.exception.DuplicateKeyException;
import io.quarkiverse.jimmer.runtime.exception.ForeignKeyViolationException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * With {@code constraint-violation-translatable=false} the built-in {@code SqlStateExceptionTranslator}
 * maps raw SQL failures to {@code JimmerDataAccessException} subtypes by SQLState.
 */
@QuarkusTest
public class ConstraintViolationTestCase {

    @Inject
    BookStoreRepository bookStoreRepository;

    @Inject
    BookRepository bookRepository;

    @Inject
    ConsumerAliasRepository consumerAliasRepository;

    @Inject
    TeamAliasRepository teamAliasRepository;

    /**
     * Unique key (business_key_book_store on name) → SQLState 23505 → DuplicateKeyException.
     * INSERT_ONLY forces Jimmer to insert a row with the already-taken name 'MANNING'.
     */
    @Test
    @TestTransaction
    void testDuplicateKeyMappedException() {
        BookStore store = Immutables.createBookStore(draft -> {
            draft.setName("MANNING"); // already exists (id=2)
            draft.setWebsite("dup");
        });

        DuplicateKeyException ex = Assertions.assertThrows(DuplicateKeyException.class,
                () -> bookStoreRepository.save(store, SaveMode.INSERT_ONLY));

        Assertions.assertEquals("23505", ex.getSqlState());
        Assertions.assertNotNull(ex.getSqlException());
        Assertions.assertEquals("book_store", ex.getTableName());
        Assertions.assertEquals("public", ex.getSchemaName());
        Assertions.assertEquals("business_key_book_store", ex.getConstraintName());
        Assertions.assertNotNull(ex.getDetail());
        Assertions.assertEquals(ImmutableType.get(BookStore.class), ex.getImmutableType());
    }

    @Test
    @TestTransaction
    void testSingleTableDuplicateKeyResolvesConsumerSubtype() {
        ConsumerAlias alias = Immutables.createConsumerAlias(draft -> draft.setValue("reserved"));

        DuplicateKeyException ex = Assertions.assertThrows(DuplicateKeyException.class,
                () -> consumerAliasRepository.save(alias, SaveMode.INSERT_ONLY));

        Assertions.assertEquals("alias", ex.getTableName());
        Assertions.assertEquals("public", ex.getSchemaName());
        Assertions.assertEquals("business_key_alias", ex.getConstraintName());
        Assertions.assertNotNull(ex.getDetail());
        Assertions.assertEquals(ImmutableType.get(ConsumerAlias.class), ex.getImmutableType());
    }

    @Test
    @TestTransaction
    void testSingleTableDuplicateKeyResolvesTeamSubtype() {
        TeamAlias alias = Immutables.createTeamAlias(draft -> draft.setValue("reserved"));

        DuplicateKeyException ex = Assertions.assertThrows(DuplicateKeyException.class,
                () -> teamAliasRepository.save(alias, SaveMode.INSERT_ONLY));

        Assertions.assertEquals(ImmutableType.get(TeamAlias.class), ex.getImmutableType());
    }

    @Test
    @TestTransaction
    void testSingleTableUpdateDuplicateKeyResolvesRootType() {
        ConsumerAlias alias = Immutables.createConsumerAlias(draft -> {
            draft.setId(2L);
            draft.setValue("reserved");
        });

        DuplicateKeyException ex = Assertions.assertThrows(DuplicateKeyException.class,
                () -> consumerAliasRepository.save(alias, SaveMode.UPDATE_ONLY));

        Assertions.assertEquals(ImmutableType.get(Alias.class), ex.getImmutableType());
    }

    /**
     * Foreign key (fk_book__book_store) → SQLState 23503 → ForeignKeyViolationException.
     * Insert a Book pointing at a non-existent store.
     */
    @Test
    @TestTransaction
    void testForeignKeyMappedException() {
        Book book = Immutables.createBook(draft -> {
            draft.setName("Dangling FK");
            draft.setEdition(1);
            draft.setPrice(new BigDecimal("1"));
            draft.setTenant("test");
            draft.setStoreId(9999L); // no such store
        });

        ForeignKeyViolationException ex = Assertions.assertThrows(ForeignKeyViolationException.class,
                () -> bookRepository.save(book, SaveMode.INSERT_ONLY));

        Assertions.assertEquals("23503", ex.getSqlState());
    }

    /**
     * Optimistic lock is Jimmer's own SaveException, NOT a SQL constraint — the SQLState translator
     * does not touch it, so it stays a SaveException.OptimisticLockError regardless of the flags.
     */
    @Test
    @TestTransaction
    void testOptimisticLockStaysSaveException() {
        BookStore fresh = Immutables.createBookStore(draft -> {
            draft.setId(1L);
            draft.setVersion(0);
            draft.setName("O'REILLY");
            draft.setWebsite("first");
        });
        bookStoreRepository.save(fresh, SaveMode.UPDATE_ONLY); // version 0 -> 1

        BookStore stale = Immutables.createBookStore(draft -> {
            draft.setId(1L);
            draft.setVersion(0); // stale
            draft.setName("O'REILLY");
            draft.setWebsite("second");
        });

        Assertions.assertThrows(SaveException.OptimisticLockError.class,
                () -> bookStoreRepository.save(stale, SaveMode.UPDATE_ONLY));
    }
}
