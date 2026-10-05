package org.babyfish.jimmer.sql.ast.impl.query;

import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.ExecutionPurpose;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>Opaque, immutable proof that a freshly rendered and executed <em>filtered</em>
 * SQL skeleton already established the membership of an exact id to concrete-type
 * group on an exact owned connection, so the ordinary per-id visibility re-check is
 * redundant for that group and would otherwise issue a second, identical query.</p>
 *
 * <p>The class is public only because the bridge lives in another package; its
 * constructor is package-private, so only {@link ObjectCacheQueryExecution} can mint
 * one, and only after the filtered skeleton SQL has returned these exact ids. There
 * is deliberately no public factory, flag or registry: an id/type group that was not
 * produced by that skeleton cannot obtain a seed.</p>
 *
 * <p>{@link #admits(ObjectCacheQuerySeed, JSqlClientImplementor, Connection,
 * ExecutionPurpose, boolean, ImmutableType, ImmutableType, Map)} re-binds the seed to
 * the exact executing client, connection and concrete-type group and re-evaluates the
 * executing manager's own inactivity proof, so a reused, forged, mismatched, external
 * or now-active token declines before any cache access.</p>
 */
public final class ObjectCacheQuerySeed {

    private final JSqlClientImplementor sqlClient;

    private final Connection connection;

    private final ConnectionManager connectionManager;

    private final ImmutableType requestedType;

    private final ImmutableType cacheOwnerType;

    private final Map<Object, ImmutableType> expectedTypes;

    ObjectCacheQuerySeed(
            JSqlClientImplementor sqlClient,
            Connection connection,
            ConnectionManager connectionManager,
            ImmutableType requestedType,
            ImmutableType cacheOwnerType,
            Map<Object, ImmutableType> expectedTypes
    ) {
        this.sqlClient = sqlClient;
        this.connection = connection;
        this.connectionManager = connectionManager;
        this.requestedType = requestedType;
        this.cacheOwnerType = cacheOwnerType;
        // Immutable copy: a later mutation of the caller's group cannot widen the proof.
        this.expectedTypes = new LinkedHashMap<>(expectedTypes);
    }

    /**
     * Authenticates a seed against the exact bindings it was minted for. Every check
     * happens before any cache access; a {@code false} result must make the caller
     * decline the whole optimization and fall back to the original query.
     */
    public static boolean admits(
            ObjectCacheQuerySeed seed,
            JSqlClientImplementor sqlClient,
            Connection con,
            ExecutionPurpose purpose,
            boolean forUpdate,
            ImmutableType requestedType,
            ImmutableType cacheOwnerType,
            Map<Object, ImmutableType> expectedTypes
    ) {
        if (seed == null || forUpdate) {
            return false;
        }
        if (purpose == null || purpose.getType() != ExecutionPurpose.Type.QUERY) {
            return false;
        }
        if (seed.sqlClient != sqlClient || seed.connection != con) {
            return false;
        }
        if (seed.requestedType != requestedType || seed.cacheOwnerType != cacheOwnerType) {
            return false;
        }
        if (!sameTypes(seed.expectedTypes, expectedTypes)) {
            return false;
        }
        // The proof is bound to the manager and connection identity that granted it and
        // is re-evaluated now, so an active, foreign or unknown connection declines.
        return seed.connectionManager != null
                && seed.connectionManager.isTransactionKnownInactive(con);
    }

    private static boolean sameTypes(Map<Object, ImmutableType> a, Map<Object, ImmutableType> b) {
        if (a == b) {
            return true;
        }
        if (b == null || a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<Object, ImmutableType> e : a.entrySet()) {
            if (b.get(e.getKey()) != e.getValue()) {
                return false;
            }
        }
        return true;
    }
}
