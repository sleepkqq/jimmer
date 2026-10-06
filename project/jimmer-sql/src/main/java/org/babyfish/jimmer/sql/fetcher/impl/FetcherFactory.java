package org.babyfish.jimmer.sql.fetcher.impl;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.babyfish.jimmer.sql.fetcher.Field;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BiPredicate;

public class FetcherFactory {

    private FetcherFactory() {}

    public static <E> Fetcher<E> filter(
            Fetcher<E> self,
            BiPredicate<ImmutableType, List<ImmutableProp>> typePredicate,
            BiPredicate<ImmutableProp, List<ImmutableProp>> propPredicate
    ) {
        if (typePredicate == null && propPredicate == null) {
            return self;
        }
        return filterImpl(
                (FetcherImpl<E>) self,
                typePredicate,
                propPredicate != null ? (type, prop, path) -> propPredicate.test(prop, path) : null,
                new LinkedList<>()
        );
    }

    public static <E> Fetcher<E> filterByTypedProp(
            Fetcher<E> self,
            BiPredicate<ImmutableType, List<ImmutableProp>> typePredicate,
            PropFilter propPredicate
    ) {
        if (typePredicate == null && propPredicate == null) {
            return self;
        }
        return filterImpl((FetcherImpl<E>) self, typePredicate, propPredicate, new LinkedList<>());
    }

    private static <E> FetcherImpl<E> filterImpl(
            FetcherImpl<E> self,
            BiPredicate<ImmutableType, List<ImmutableProp>> typePredicate,
            PropFilter propPredicate,
            LinkedList<ImmutableProp> path
    ) {
        if (self == null) {
            return null;
        }
        if (typePredicate != null && !typePredicate.test(self.getImmutableType(), Collections.unmodifiableList(path))) {
            return null;
        }
        FetcherImpl<E> filteredPrevFetcher = filterImpl(self.prev, typePredicate, propPredicate, path);
        if (self.typeBranchFetcher != null) {
            FetcherImpl<?> filteredTypeBranchFetcher = filterImpl(
                    self.typeBranchFetcher,
                    typePredicate,
                    propPredicate,
                    path
            );
            return filteredTypeBranchFetcher != null ?
                    new FetcherImpl<>(filteredPrevFetcher, filteredTypeBranchFetcher) :
                    filteredPrevFetcher;
        }
        if (!self.negative && self.prop != null && !self.prop.isId()) {
            if (propPredicate != null && !propPredicate.test(
                    self.getImmutableType(),
                    self.prop,
                    Collections.unmodifiableList(path)
            )) {
                return filteredPrevFetcher;
            }
            FetcherImpl<?> childFetcher = self.childFetcher;
            if (childFetcher != null) {
                path.addLast(self.prop);
                FetcherImpl<?> filteredChildFetcher = filterImpl(childFetcher, typePredicate, propPredicate, path);
                path.pollLast();
                if (filteredChildFetcher == null) {
                    return filteredPrevFetcher;
                }
                return new FetcherImpl<>(filteredPrevFetcher, self, filteredChildFetcher);
            }
        }
        return new FetcherImpl<>(filteredPrevFetcher, self, self.childFetcher);
    }

    /**
     * Like {@link #filter}, but walks the fetcher's native-expanded field map - which
     * includes the implicit dependency fields {@link FetcherImpl#getFieldMap()} synthesizes
     * for a JVM formula - instead of the raw declaration chain. A caller that removes a
     * formula must use this: filtering the declaration chain alone silently drops the
     * formula's stored dependency containers, because they only exist in the expanded map.
     */
    public static <E> Fetcher<E> filterExpanded(
            Fetcher<E> self,
            BiPredicate<ImmutableProp, List<ImmutableProp>> propPredicate
    ) {
        if (self == null) {
            return null;
        }
        return filterExpandedImpl((FetcherImpl<E>) self, propPredicate, new LinkedList<>());
    }

    private static <E> FetcherImpl<E> filterExpandedImpl(
            FetcherImpl<E> self,
            BiPredicate<ImmutableProp, List<ImmutableProp>> propPredicate,
            LinkedList<ImmutableProp> path
    ) {
        FetcherImpl<E> result = new FetcherImpl<>(self.getJavaClass());
        for (Fetcher<?> typeBranchFetcher : self.__getTypeBranchFetcherMap().values()) {
            result = (FetcherImpl<E>) result.__forType(typeBranchFetcher);
        }
        for (Field field : self.getFieldMap().values()) {
            ImmutableProp prop = field.getProp();
            if (prop.isId()) {
                continue;
            }
            if (propPredicate != null && !propPredicate.test(prop, Collections.unmodifiableList(path))) {
                continue;
            }
            path.addLast(prop);
            FetcherImpl<?> child = null;
            Fetcher<?> childFetcher = field.getChildFetcher();
            if (childFetcher != null) {
                child = filterExpandedImpl((FetcherImpl<?>) childFetcher, propPredicate, path);
            }
            path.pollLast();
            result = new FetcherImpl<>(result, field, child);
        }
        return result;
    }

    public static <E> Fetcher<E> excludeMicroServiceNameExceptRoot(
            Fetcher<E> fetcher,
            String microServiceName
    ) {
        return filter(
                fetcher,
                (type, path) -> path.isEmpty() || !type.getMicroServiceName().equals(microServiceName),
                (BiPredicate<ImmutableProp, List<ImmutableProp>>) null
        );
    }

    @FunctionalInterface
    public interface PropFilter {

        boolean test(ImmutableType type, ImmutableProp prop, List<ImmutableProp> path);
    }
}
