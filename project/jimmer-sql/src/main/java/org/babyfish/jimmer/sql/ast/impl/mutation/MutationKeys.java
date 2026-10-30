package org.babyfish.jimmer.sql.ast.impl.mutation;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.meta.LogicalDeletedInfo;
import org.babyfish.jimmer.sql.cache.Caches;
import org.babyfish.jimmer.sql.event.TriggerType;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.babyfish.jimmer.sql.runtime.MutationKeyListener;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

class MutationKeys {

    private MutationKeys() {
    }

    /**
     * The listener that needs the ids of mutated {@code type} rows, or {@code null}. Keys are
     * only worth an extra id pre-select when a cache is keyed by those ids, so a client without
     * such a cache keeps its direct statements.
     */
    @Nullable
    static MutationKeyListener listenerOf(JSqlClientImplementor sqlClient, ImmutableType type) {
        MutationKeyListener listener = listenerOf(sqlClient);
        return listener != null && isKeyedByCache(sqlClient.getCaches(), type) ? listener : null;
    }

    static void fireEntitiesMutated(
            JSqlClientImplementor sqlClient,
            Connection con,
            ImmutableType type,
            Collection<?> ids
    ) {
        MutationKeyListener listener = listenerOf(sqlClient, type);
        if (listener != null && !ids.isEmpty()) {
            listener.entitiesMutated(con, type, ids);
        }
    }

    static void fireAssociationsMutated(
            JSqlClientImplementor sqlClient,
            Connection con,
            ImmutableProp prop,
            Collection<?> sourceIds
    ) {
        MutationKeyListener listener = listenerOf(sqlClient);
        if (listener != null &&
                !sourceIds.isEmpty() &&
                sqlClient.getCaches().getPropertyCache(prop) != null) {
            listener.associationsMutated(con, prop, sourceIds);
        }
    }

    @Nullable
    private static MutationKeyListener listenerOf(JSqlClientImplementor sqlClient) {
        if (sqlClient.getTriggerType() != TriggerType.BINLOG_ONLY) {
            return null;
        }
        ConnectionManager connectionManager = sqlClient.getConnectionManager();
        return connectionManager instanceof MutationKeyListener ?
                (MutationKeyListener) connectionManager :
                null;
    }

    private static boolean isKeyedByCache(Caches caches, ImmutableType type) {
        return isKeyedByCache(caches, type.getAllTypes()) ||
                isKeyedByCache(caches, type.getAllDerivedTypes());
    }

    private static boolean isKeyedByCache(Caches caches, Collection<ImmutableType> types) {
        for (ImmutableType t : types) {
            if (caches.getObjectCache(t) != null) {
                return true;
            }
            for (ImmutableProp prop : t.getProps().values()) {
                if (prop.getDeclaringType().isEntity() && caches.getPropertyCache(prop) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    static ImmutableProp activeStateKeyProp(ImmutableType type) {
        LogicalDeletedInfo info = type.getLogicalDeletedInfo();
        // Unlike boolean flags or generated deletion markers, enum/int flags can have several live values.
        return info != null && info.getAction() instanceof LogicalDeletedInfo.Action.Ne &&
                (info.getType().isEnum() || info.getType() == int.class) ? info.getProp() : null;
    }

    static Collection<ImmutableProp> matchingKeyProps(ImmutableType type, Collection<ImmutableProp> keyProps) {
        ImmutableProp prop = activeStateKeyProp(type);
        if (prop == null || keyProps.contains(prop)) {
            return keyProps;
        }
        List<ImmutableProp> props = new ArrayList<>(keyProps);
        addProp(props, prop);
        return props;
    }

    static List<ImmutableProp> keyAndLogicalDeletedProps(
            ImmutableType type,
            Collection<ImmutableProp> keyProps
    ) {
        List<ImmutableProp> props = new ArrayList<>(keyProps);
        LogicalDeletedInfo logicalDeletedInfo = type.getLogicalDeletedInfo();
        if (logicalDeletedInfo != null) {
            addProp(props, logicalDeletedInfo.getProp());
        }
        return props;
    }

    @Nullable
    static LogicalDeletedInfo logicalDeletedConflictPredicate(ImmutableType type) {
        LogicalDeletedInfo logicalDeletedInfo = type.getLogicalDeletedInfo();
        return logicalDeletedInfo != null && logicalDeletedInfo.getType() == boolean.class ?
                logicalDeletedInfo :
                null;
    }

    private static void addProp(List<ImmutableProp> props, ImmutableProp prop) {
        ImmutableProp originalProp = prop.toOriginal();
        for (ImmutableProp existingProp : props) {
            if (existingProp.toOriginal() == originalProp) {
                return;
            }
        }
        props.add(prop);
    }
}
