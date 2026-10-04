package org.babyfish.jimmer.sql.model.calc;

import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.TransientResolver;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.event.ChangedRef;
import org.babyfish.jimmer.sql.event.EntityEvent;
import org.babyfish.jimmer.sql.model.Book;
import org.babyfish.jimmer.sql.model.BookProps;
import org.babyfish.jimmer.sql.model.BookStore;
import org.babyfish.jimmer.sql.model.BookStoreTable;
import org.babyfish.jimmer.sql.model.BookTable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public class BookStoreNewestBooksResolver implements TransientResolver<UUID, List<UUID>> {

    private static final BookStoreTable table = BookStoreTable.$;

    private final JSqlClient sqlClient;

    public BookStoreNewestBooksResolver(JSqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @Override
    public Map<UUID, List<UUID>> resolve(Collection<UUID> ids) {
        BookTable book = new BookTable();
        List<Tuple2<UUID, UUID>> tuples = sqlClient.createQuery(table)
                .where(
                        Expression.tuple(
                                table.asTableEx().books().name(),
                                table.asTableEx().books().edition()
                        ).in(
                                sqlClient
                                        .createSubQuery(book)
                                        .where(book.store().id().in(ids))
                                        .groupBy(book.name())
                                        .select(
                                                book.name(),
                                                book.edition().max()
                                        )
                        )
                )
                .select(
                        table.id(),
                        table.asTableEx().books().id()
                )
                .execute(TransientResolver.currentConnection());
        return tuples.stream().collect(
                Collectors.groupingBy(
                        Tuple2<UUID, UUID>::get_1,
                        Collectors.mapping(
                                Tuple2<UUID, UUID>::get_2,
                                Collectors.toList()
                        )
                )
        );
    }

    @Nullable
    @Override
    public Collection<?> getAffectedSourceIds(@NonNull EntityEvent<?> e) {
        if (e.isEvict() || e.getImmutableType().getJavaClass() != Book.class) {
            return null;
        }
        Set<UUID> ids = new LinkedHashSet<>(2);
        ChangedRef<BookStore> storeRef = e.getChangedRef(BookProps.STORE);
        if (storeRef != null) {
            ChangedRef<Object> idRef = storeRef.toIdRef();
            if (idRef.getOldValue() != null) {
                ids.add((UUID) idRef.getOldValue());
            }
            if (idRef.getNewValue() != null) {
                ids.add((UUID) idRef.getNewValue());
            }
        } else if (e.isChanged(BookProps.NAME) || e.isChanged(BookProps.EDITION)) {
            BookStore store = e.getUnchangedValue(BookProps.STORE);
            if (store != null) {
                ids.add(store.id());
            }
        }
        return ids.isEmpty() ? null : ids;
    }
}
