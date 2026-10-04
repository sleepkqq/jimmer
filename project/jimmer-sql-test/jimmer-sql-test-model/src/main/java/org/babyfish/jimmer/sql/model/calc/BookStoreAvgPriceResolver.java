package org.babyfish.jimmer.sql.model.calc;

import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.JoinType;
import org.babyfish.jimmer.sql.TransientResolver;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.event.AssociationEvent;
import org.babyfish.jimmer.sql.event.ChangedRef;
import org.babyfish.jimmer.sql.event.EntityEvent;
import org.babyfish.jimmer.sql.model.Book;
import org.babyfish.jimmer.sql.model.BookProps;
import org.babyfish.jimmer.sql.model.BookStore;
import org.babyfish.jimmer.sql.model.BookStoreProps;
import org.babyfish.jimmer.sql.model.BookStoreTable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.*;

public class BookStoreAvgPriceResolver implements TransientResolver<UUID, BigDecimal> {

    protected static final BookStoreTable table = BookStoreTable.$;

    private final JSqlClient sqlClient;

    public BookStoreAvgPriceResolver(JSqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @Override
    public Map<UUID, BigDecimal> resolve(Collection<UUID> ids) {
        List<Tuple2<UUID, BigDecimal>> tuples =
                sqlClient.createQuery(table)
                        .where(table.id().in(ids))
                        .groupBy(table.id())
                        .select(
                                table.id(),
                                Expression.numeric().sql(
                                        BigDecimal.class,
                                        "round(%e, 2)",
                                        table.asTableEx().books(JoinType.LEFT).price().avgAsDecimal()
                                ).coalesce(BigDecimal.ZERO)
                        )
                        .execute(TransientResolver.currentConnection());
        return Tuple2.toMap(tuples);
    }

    @Nullable
    @Override
    public Collection<?> getAffectedSourceIds(@NonNull AssociationEvent e) {
        if (e.getImmutableProp() == BookStoreProps.BOOKS.unwrap()) {
            return Collections.singleton(e.getSourceId());
        }
        return null;
    }

    @Nullable
    @Override
    public Collection<?> getAffectedSourceIds(@NonNull EntityEvent<?> e) {
        if (e.isEvict() || e.getImmutableType().getJavaClass() != Book.class) {
            return null;
        }
        ChangedRef<BookStore> storeRef = e.getChangedRef(BookProps.STORE);
        if (storeRef != null) {
            ChangedRef<Object> idRef = storeRef.toIdRef();
            Set<UUID> ids = new LinkedHashSet<>(2);
            if (idRef.getOldValue() != null) {
                ids.add((UUID) idRef.getOldValue());
            }
            if (idRef.getNewValue() != null) {
                ids.add((UUID) idRef.getNewValue());
            }
            return ids.isEmpty() ? null : ids;
        }
        if (e.isChanged(BookProps.PRICE)) {
            BookStore store = e.getUnchangedValue(BookProps.STORE);
            return store != null ? Collections.singleton(store.id()) : null;
        }
        return null;
    }
}
