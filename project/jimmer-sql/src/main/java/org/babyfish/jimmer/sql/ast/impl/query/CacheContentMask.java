package org.babyfish.jimmer.sql.ast.impl.query;

import org.babyfish.jimmer.meta.EmbeddedLevel;
import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.meta.InheritanceInfo;
import org.babyfish.jimmer.meta.TargetLevel;
import org.babyfish.jimmer.runtime.DraftSpi;
import org.babyfish.jimmer.runtime.ImmutableSpi;
import org.babyfish.jimmer.runtime.Internal;
import org.babyfish.jimmer.sql.InheritanceType;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.babyfish.jimmer.sql.fetcher.Field;
import org.babyfish.jimmer.sql.fetcher.impl.FetcherFactory;
import org.babyfish.jimmer.sql.fetcher.impl.JoinFetchFieldVisitor;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>A recursive, path-specific whitelist of object-cache content for
 * {@link ObjectCacheQueryExecution}. Stored scalar leaves, embedded leaves and the
 * stored dependency closure of an approved JVM formula are served from the owning
 * entity's object cache. Association entries navigate into the child entity content;
 * they never authorize caching the edge itself. Collections, id-views, remote and
 * SQL-formula properties are rejected, and optimistic-lock / logical-delete protected
 * properties are rejected by metadata rather than by name.</p>
 *
 * <p>{@link #retainedFetcher} derives the SQL projection by removing only a node's own
 * approved leaves when that node's owning entity actually has an object cache, so SQL
 * still supplies every fresh scalar, FK edge, membership, nullability and concrete
 * type. An embedded value is never reduced to zero columns: a whole-embedded approval
 * and a nullable embedded keep their SQL columns, and a required embedded whose every
 * selected leaf would be removed keeps at least its original projection. This preserves
 * the current presence/nullability that a zero-field reader would fabricate as absent.
 * {@link #overlay} then fills exactly the removed leaves from the cached payloads and
 * never merges a cache payload as a whole.</p>
 */
final class CacheContentMask {

    private final ImmutableType type;

    private final boolean embedded;

    /** Whether an embedded node may be absent; such a node never has its columns removed. */
    private final boolean nullable;

    /** The entity that owns the cache for an embedded node; equals {@link #type} otherwise. */
    private final ImmutableType cacheOwnerType;

    private final Map<ImmutableProp, CacheContentMask> children;

    private final Set<ImmutableProp> leaves;

    /** Whitelisted JVM formulas: removed from the retained projection but never cached. */
    private final Set<ImmutableProp> formulas;

    private CacheContentMask(
            ImmutableType type,
            boolean embedded,
            boolean nullable,
            ImmutableType cacheOwnerType,
            Map<ImmutableProp, CacheContentMask> children,
            Set<ImmutableProp> leaves,
            Set<ImmutableProp> formulas
    ) {
        this.type = type;
        this.embedded = embedded;
        this.nullable = nullable;
        this.cacheOwnerType = cacheOwnerType;
        this.children = children;
        this.leaves = leaves;
        this.formulas = formulas;
    }

    ImmutableType getType() {
        return type;
    }

    Map<ImmutableProp, CacheContentMask> getChildren() {
        return children;
    }

    static CacheContentMask of(Fetcher<?> fetcher) {
        return of(fetcher, false, false, null);
    }

    private static CacheContentMask of(Fetcher<?> fetcher, boolean embedded, boolean nullable, ImmutableType ownerType) {
        if (fetcher == null) {
            throw new IllegalArgumentException("The object-cache content mask cannot be null");
        }
        ImmutableType type = fetcher.getImmutableType();
        ImmutableType cacheOwnerType = embedded ? ownerType : type;
        Map<ImmutableProp, CacheContentMask> children = new LinkedHashMap<>();
        Set<ImmutableProp> leaves = new LinkedHashSet<>();
        Set<ImmutableProp> formulas = new LinkedHashSet<>();
        for (Field field : fetcher.getFieldMap().values()) {
            ImmutableProp prop = field.getProp();
            if (prop.isId() || prop.isDiscriminator()) {
                continue;
            }
            if (prop.isVersion() || prop.isLogicalDeleted()) {
                throw new IllegalArgumentException(
                        "The object-cache content mask cannot cache protected property \"" + prop + "\""
                );
            }
            if (prop.isFormula()) {
                if (prop.getSqlTemplate() != null) {
                    throw new IllegalArgumentException(
                            "The object-cache content mask cannot cache SQL formula \"" + prop + "\""
                    );
                }
                // A JVM formula is removed from SQL and recomputed by its getter. Its
                // stored dependency closure is already expanded by the native fetcher
                // (FetcherImpl#getFieldMap), including nested stored paths and
                // formula-to-formula edges, so the implicit dependency fields present
                // here are approved as ordinary stored leaves below - no homemade
                // expander is needed, and a native closure that cannot be stored
                // (association edge, SQL formula, protected prop) is rejected by the
                // same generic branch.
                formulas.add(prop);
                continue;
            }
            if (field.getRecursionStrategy() != null) {
                throw new IllegalArgumentException(
                        "The object-cache content mask does not support recursive property \"" + prop + "\""
                );
            }
            if (prop.isEmbedded(EmbeddedLevel.SCALAR)) {
                Fetcher<?> childFetcher = field.getChildFetcher();
                if (childFetcher == null) {
                    // The whole embedded value is approved. It is copied as one unit and,
                    // because a whole-embedded leaf carries no nested loadedness, is never
                    // removed from SQL; the current columns stay authoritative.
                    leaves.add(prop);
                } else {
                    children.put(
                            prop,
                            of(childFetcher, true, prop.isNullable(), cacheOwnerType)
                    );
                }
                continue;
            }
            if (prop.isAssociation(TargetLevel.PERSISTENT)) {
                if (!prop.isReference(TargetLevel.PERSISTENT) || prop.isReferenceList(TargetLevel.PERSISTENT)) {
                    throw new IllegalArgumentException(
                            "The object-cache content mask does not support collection association \"" + prop + "\""
                    );
                }
                Fetcher<?> childFetcher = field.getChildFetcher();
                if (childFetcher == null) {
                    throw new IllegalArgumentException(
                            "The object-cache content mask cannot cache the edge of association \"" +
                                    prop +
                                    "\"; declare the child content to navigate into it"
                    );
                }
                children.put(prop, of(childFetcher, false, false, null));
                continue;
            }
            if (prop.isView() || prop.isRemote() || !prop.hasStorage() || prop.getSqlTemplate() != null) {
                throw new IllegalArgumentException(
                        "The object-cache content mask only supports stored local properties, but \"" +
                                prop +
                                "\" is not one"
                );
            }
            leaves.add(prop);
        }
        return new CacheContentMask(
                type,
                embedded,
                nullable,
                cacheOwnerType,
                children,
                leaves,
                formulas
        );
    }

    /**
     * A copy of this mask with all navigated children dropped, keeping only this node's
     * own leaves. Used for a bare entity-table selection, whose partial form cannot
     * express association content selected by other tuple slots.
     */
    CacheContentMask withoutChildren() {
        return new CacheContentMask(
                type,
                embedded,
                nullable,
                cacheOwnerType,
                new LinkedHashMap<>(),
                leaves,
                formulas
        );
    }

    /**
     * Builds the SQL projection for the retained (fresh) read: the caller's projection
     * with every approved stored leaf of a cacheable node removed. Associations are kept
     * (as an empty id-only child when all of their content is approved) so their edges
     * stay SQL-authoritative.
     *
     * @return the retained fetcher, or {@code null} when a whitelisted nested association
     * resolves to an effective SQL join whose reduction to id-only would drop the join
     * that proves the target exists, when the mask would reduce a reference carrying a
     * field-local filter and thereby erase its admission boundary, or when the projection
     * selects a recursive association whose derived child cannot be partially reduced; the
     * caller declines instead of manufacturing the target or corrupting the recursion
     * @throws IllegalArgumentException when the mask does not match the selected
     * fetcher or lists content it does not select
     */
    static Fetcher<?> retainedFetcher(
            Fetcher<?> projection,
            CacheContentMask mask,
            JSqlClientImplementor sqlClient
    ) {
        if (projection.getImmutableType() != mask.type) {
            throw new IllegalArgumentException(
                    "The object-cache content mask root type \"" +
                            mask.type +
                            "\" does not match the selected type \"" +
                            projection.getImmutableType() +
                            "\""
            );
        }
        if (!isSubset(projection, mask)) {
            throw new IllegalArgumentException(
                    "The object-cache content mask lists content that the selected fetcher does not select"
            );
        }
        // Native recursion derives children from the parent projection; reducing it
        // would also drop unapproved descendant fields.
        if (hasRecursiveProjection(projection)) {
            return null;
        }
        Set<List<ImmutableProp>> keepEmbedded = new LinkedHashSet<>();
        collectZeroingEmbedded(projection, mask, new ArrayList<>(), sqlClient, keepEmbedded);
        if (hasJoinedNestedMask(projection, mask, new ArrayList<>(), sqlClient)) {
            return null;
        }
        if (hasReducedFieldLocalFilter(projection, mask)) {
            return null;
        }
        // Filter the native-expanded field map, not the declaration chain: the stored
        // dependency containers of a removed JVM formula only exist in the expanded map,
        // so a chain filter would drop them and leave the formula getter unloaded.
        return FetcherFactory.filterExpanded(
                projection,
                (prop, path) -> isPrefixOfAny(keepEmbedded, path) ||
                        !mask.isRemovedAt(sqlClient, path, prop)
        );
    }

    /**
     * Whether the mask would reduce a reference whose projection carries a field-local
     * filter to an id-only edge. That reduction erases the filter's admission boundary:
     * the id-only reference is no longer a filtered target read, so a warm or
     * freshly-loaded target would bypass the filter and be fabricated from the parent
     * foreign key. Decline the whole hint rather than erase the boundary.
     */
    private static boolean hasReducedFieldLocalFilter(Fetcher<?> projection, CacheContentMask node) {
        for (Field field : projection.getFieldMap().values()) {
            ImmutableProp prop = field.getProp();
            if (prop.isId() || prop.isDiscriminator()) {
                continue;
            }
            CacheContentMask childNode = node.children.get(prop);
            Fetcher<?> childFetcher = field.getChildFetcher();
            if (childNode == null || childFetcher == null) {
                continue;
            }
            if (prop.isAssociation(TargetLevel.PERSISTENT) && field.getFilter() != null) {
                return true;
            }
            if (hasReducedFieldLocalFilter(childFetcher, childNode)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRecursiveProjection(Fetcher<?> projection) {
        for (Field field : projection.getFieldMap().values()) {
            if (field.getRecursionStrategy() != null) {
                return true;
            }
            Fetcher<?> childFetcher = field.getChildFetcher();
            if (childFetcher != null && hasRecursiveProjection(childFetcher)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrefixOfAny(Set<List<ImmutableProp>> paths, List<ImmutableProp> candidate) {
        for (List<ImmutableProp> path : paths) {
            if (candidate.size() >= path.size() && candidate.subList(0, path.size()).equals(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records the paths of embedded values whose every selected leaf (including nested
     * embedded descendants) would be removed, so the retained fetcher must keep their
     * original SQL columns. Otherwise a zero-field reader would turn a currently present
     * (or NULL) embedded into an absent one, and a nullable parent whose only columns come
     * from such a child could itself be fabricated as absent. A nullable embedded's own
     * leaves are never removed, so it is recorded only through this nested protection.
     */
    private static void collectZeroingEmbedded(
            Fetcher<?> fetcher,
            CacheContentMask rootMask,
            List<ImmutableProp> path,
            JSqlClientImplementor sqlClient,
            Set<List<ImmutableProp>> out
    ) {
        for (Field field : fetcher.getFieldMap().values()) {
            ImmutableProp prop = field.getProp();
            if (prop.isId() || prop.isDiscriminator()) {
                continue;
            }
            Fetcher<?> childFetcher = field.getChildFetcher();
            if (prop.isEmbedded(EmbeddedLevel.SCALAR)) {
                if (childFetcher == null) {
                    // A whole-embedded leaf keeps its SQL columns, so it can never be
                    // reduced to a zero-field reader.
                    continue;
                }
                // Descend through a nullable embedded too: its own columns are never
                // removed, but a required embedded nested below it can still have every
                // selected leaf removed. Such a value must keep its SQL columns or a
                // zero-field reader would turn a currently present value into an absent
                // one (and, when the nullable parent has no direct column, the parent
                // would be fabricated as absent as well).
                List<ImmutableProp> childPath = new ArrayList<>(path);
                childPath.add(prop);
                if (isContentFullyRemoved(childFetcher, childPath, rootMask, sqlClient)) {
                    out.add(childPath);
                }
                collectZeroingEmbedded(childFetcher, rootMask, childPath, sqlClient, out);
            } else if (prop.isAssociation(TargetLevel.PERSISTENT) && childFetcher != null) {
                List<ImmutableProp> childPath = new ArrayList<>(path);
                childPath.add(prop);
                collectZeroingEmbedded(childFetcher, rootMask, childPath, sqlClient, out);
            }
        }
    }

    private static boolean isSubset(Fetcher<?> projection, CacheContentMask mask) {
        Map<String, Field> fieldMap = projection.getFieldMap();
        for (ImmutableProp leaf : mask.leaves) {
            if (!fieldMap.containsKey(leaf.getName())) {
                return false;
            }
        }
        for (ImmutableProp formula : mask.formulas) {
            if (!fieldMap.containsKey(formula.getName())) {
                return false;
            }
        }
        for (Map.Entry<ImmutableProp, CacheContentMask> e : mask.children.entrySet()) {
            Field field = fieldMap.get(e.getKey().getName());
            if (field == null || field.getChildFetcher() == null) {
                return false;
            }
            if (!isSubset(field.getChildFetcher(), e.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean isRemovedAt(
            JSqlClientImplementor sqlClient,
            List<ImmutableProp> path,
            ImmutableProp prop
    ) {
        CacheContentMask node = this;
        for (ImmutableProp step : path) {
            node = node.children.get(step);
            if (node == null) {
                return false;
            }
        }
        if (node.embedded && node.nullable) {
            // A nullable embedded's presence is a function of all of its columns;
            // removing any of them from SQL could fabricate an absent value.
            return false;
        }
        if (!node.isCacheableNode(sqlClient)) {
            return false;
        }
        if (node.leaves.contains(prop)) {
            // A whole-embedded leaf carries no nested loadedness; keep its columns.
            return !prop.isEmbedded(EmbeddedLevel.SCALAR);
        }
        return prop.isFormula() &&
                prop.getSqlTemplate() == null &&
                node.formulas.contains(prop);
    }

    /**
     * Whether this node has at least one approved own leaf that can actually be served
     * from a cache: an entity node with its own object cache, or an embedded node whose
     * owning entity has one.
     */
    boolean isCacheableNode(JSqlClientImplementor sqlClient) {
        return isCacheable(sqlClient, cacheOwnerType);
    }

    private boolean hasStoredLeaves() {
        if (!leaves.isEmpty()) {
            return true;
        }
        for (CacheContentMask child : children.values()) {
            if (child.embedded && child.hasStoredLeaves()) {
                return true;
            }
        }
        return false;
    }

    /** Whether this entity node's approved content (including embedded leaves) is cached. */
    boolean hasCacheableOwnLeaves(JSqlClientImplementor sqlClient) {
        return !embedded && isCacheableNode(sqlClient) && hasStoredLeaves();
    }

    /** Whether this node or any navigated descendant has cacheable leaves. */
    boolean hasCacheableLeaves(JSqlClientImplementor sqlClient) {
        if (isCacheableNode(sqlClient) && hasStoredLeaves()) {
            return true;
        }
        for (CacheContentMask child : children.values()) {
            if (child.hasCacheableLeaves(sqlClient)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any cacheable node in this subtree uses joined inheritance, whose partial
     * projection needs per-branch physical joins the ordinary path already covers, so the
     * hint declines. Single-table inheritance is supported because the fresh discriminator
     * stays SQL and the payload type is validated before shaping.
     */
    boolean hasJoinedInheritanceCacheable(JSqlClientImplementor sqlClient) {
        if (!embedded && isCacheableNode(sqlClient) && hasStoredLeaves()) {
            InheritanceInfo info = type.getInheritanceInfo();
            if (info != null && info.getStrategy() == InheritanceType.JOINED) {
                return true;
            }
        }
        for (CacheContentMask child : children.values()) {
            if (child.hasJoinedInheritanceCacheable(sqlClient)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects a whitelisted nested association whose reduction to id-only would turn an
     * effective SQL join into a parent-FK reference, losing the join's
     * existence/nullability/membership semantics. Only a fully removed FAKE-FK join
     * declines: a real FK still guarantees the target, and a partly retained child keeps
     * enough target columns for its loader to prove existence. The join decision uses the
     * original client, not the cache-disabled derived client, because it is the original
     * configured/effective join whose semantics must be preserved.
     */
    private static boolean hasJoinedNestedMask(
            Fetcher<?> projection,
            CacheContentMask rootMask,
            List<ImmutableProp> path,
            JSqlClientImplementor sqlClient
    ) {
        for (Map.Entry<ImmutableProp, CacheContentMask> e : rootMask.children.entrySet()) {
            ImmutableProp prop = e.getKey();
            CacheContentMask childNode = e.getValue();
            if (childNode.embedded) {
                continue;
            }
            Field field = projection.getFieldMap().get(prop.getName());
            if (field == null || field.getChildFetcher() == null) {
                continue;
            }
            List<ImmutableProp> childPath = new ArrayList<>(path);
            childPath.add(prop);
            boolean fakeUnfiltered =
                    !prop.isTargetForeignKeyReal(sqlClient.getMetadataStrategy()) &&
                            sqlClient.getFilters().getTargetFilter(prop) == null;
            if (fakeUnfiltered &&
                    JoinFetchFieldVisitor.isJoinField(field, sqlClient) &&
                    isContentFullyRemoved(field.getChildFetcher(), childPath, rootMask, sqlClient)) {
                return true;
            }
            if (hasJoinedNestedMask(field.getChildFetcher(), childNode, childPath, sqlClient)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether every selectable field of {@code fetcher} is removed by the mask, so the
     * retained fetcher would collapse to an id-only reference. An id-only nested join
     * drops the physical join; an id-only nested SELECT loses the target existence read.
     */
    private static boolean isContentFullyRemoved(
            Fetcher<?> fetcher,
            List<ImmutableProp> path,
            CacheContentMask rootMask,
            JSqlClientImplementor sqlClient
    ) {
        boolean any = false;
        boolean allRemoved = true;
        for (Field field : fetcher.getFieldMap().values()) {
            ImmutableProp prop = field.getProp();
            if (prop.isId() || prop.isDiscriminator()) {
                continue;
            }
            any = true;
            if (prop.isEmbedded(EmbeddedLevel.SCALAR)) {
                if (field.getChildFetcher() == null) {
                    // A whole-embedded leaf is never removed from SQL.
                    allRemoved = false;
                    continue;
                }
                List<ImmutableProp> childPath = new ArrayList<>(path);
                childPath.add(prop);
                if (!isContentFullyRemoved(field.getChildFetcher(), childPath, rootMask, sqlClient)) {
                    allRemoved = false;
                }
                continue;
            }
            if (prop.isAssociation(TargetLevel.PERSISTENT) && field.getChildFetcher() != null) {
                List<ImmutableProp> childPath = new ArrayList<>(path);
                childPath.add(prop);
                if (!isContentFullyRemoved(field.getChildFetcher(), childPath, rootMask, sqlClient)) {
                    allRemoved = false;
                }
                continue;
            }
            if (!rootMask.isRemovedAt(sqlClient, path, prop)) {
                allRemoved = false;
            }
        }
        return any && allRemoved;
    }

    private static boolean isCacheable(JSqlClientImplementor sqlClient, ImmutableType type) {
        if (sqlClient.getCaches().getObjectCache(type) != null) {
            return true;
        }
        for (ImmutableType derived : type.getAllDerivedTypes()) {
            if (sqlClient.getCaches().getObjectCache(derived) != null) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Fresh-seed collection, cached-content validation and overlay
    // ---------------------------------------------------------------------

    /**
     * Walks the fresh retained graph and records, per cacheable entity node, the
     * id-to-concrete-type group that must be loaded from that node's object cache.
     *
     * <p>A navigated node's id is recorded as proven only when the fresh read itself
     * establishes that exact id <em>and</em> its concrete type: a real foreign key
     * without a target filter proves existence, and a polymorphic target additionally
     * requires its freshly read discriminator. Proof is tracked per exact id, never per
     * node, because different slots (a joined slot and a navigated FAKE-FK reference) can
     * reach the same node with different ids. Any unproven id keeps its fresh per-id
     * existence read, so a FAKE foreign key can never resurrect a warm but deleted target
     * through a sibling slot's proof.
     *
     * @return {@code false} when a cacheable navigated target's concrete type cannot be
     * established from the fresh read (a polymorphic target reached through a plain FK
     * whose discriminator was not read); the caller must then fall back to the whole
     * original query instead of trusting a declared-only type
     */
    static boolean collectSeeds(
            ImmutableSpi fresh,
            CacheContentMask node,
            Map<CacheContentMask, Map<Object, ImmutableType>> seeds,
            Map<CacheContentMask, Map<Object, ImmutableType>> provenSeeds,
            JSqlClientImplementor sqlClient
    ) {
        for (Map.Entry<ImmutableProp, CacheContentMask> e : node.children.entrySet()) {
            ImmutableProp prop = e.getKey();
            if (!fresh.__isLoaded(prop.getId())) {
                continue;
            }
            Object child = fresh.__get(prop.getId());
            if (child == null) {
                continue;
            }
            ImmutableSpi childSpi = (ImmutableSpi) child;
            CacheContentMask childNode = e.getValue();
            // A navigated entity whose concrete type is not established by the fresh read
            // must not be seeded or descended into: a declared-only id reference cannot
            // prove its subtype, so the whole hint declines rather than navigate a
            // fabricated type.
            if (!childNode.embedded && !isConcreteTypeEstablished(childSpi)) {
                return false;
            }
            if (childNode.hasCacheableOwnLeaves(sqlClient)) {
                Object id = childSpi.__get(childNode.type.getIdProp().getId());
                if (id != null) {
                    ImmutableType concreteType = childSpi.__type();
                    seeds.computeIfAbsent(childNode, it -> new LinkedHashMap<>())
                            .put(id, concreteType);
                    if (isExistenceProven(prop, sqlClient)) {
                        provenSeeds.computeIfAbsent(childNode, it -> new LinkedHashMap<>())
                                .put(id, concreteType);
                    }
                }
            }
            if (!collectSeeds(childSpi, childNode, seeds, provenSeeds, sqlClient)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExistenceProven(ImmutableProp prop, JSqlClientImplementor sqlClient) {
        return prop.isTargetForeignKeyReal(sqlClient.getMetadataStrategy()) &&
                sqlClient.getFilters().getTargetFilter(prop) == null;
    }

    /**
     * Whether the fresh value establishes its own concrete type. A non-polymorphic type
     * is exact by construction. Any type that belongs to an inheritance hierarchy is
     * only concrete when the freshly read value carries its discriminator: an id-only
     * reference reader manufactures the declared type from the physical FK and must not
     * be accepted as concrete-type proof, or a stale cached sibling subtype would be
     * admitted.
     */
    private static boolean isConcreteTypeEstablished(ImmutableSpi fresh) {
        ImmutableType type = fresh.__type();
        InheritanceInfo inheritanceInfo = type.getInheritanceInfo();
        if (inheritanceInfo == null) {
            return true;
        }
        return fresh.__isLoaded(inheritanceInfo.getDiscriminatorProp(type).getId());
    }

    /**
     * Validates that every approved leaf missing from the fresh value has a loaded cached
     * counterpart, and that every navigated entity edge matches its cached target by
     * concrete type and id. A {@code false} result makes the caller fall back to the whole
     * original query exactly once.
     */
    static boolean validate(
            ImmutableSpi fresh,
            CacheContentMask node,
            Map<CacheContentMask, Map<Object, ImmutableSpi>> cachedByNode,
            JSqlClientImplementor sqlClient
    ) {
        ImmutableSpi cached = cachedOf(fresh, node, cachedByNode, sqlClient);
        return validateNode(fresh, node, cached, cachedByNode, sqlClient);
    }

    private static boolean validateNode(
            ImmutableSpi fresh,
            CacheContentMask node,
            ImmutableSpi cached,
            Map<CacheContentMask, Map<Object, ImmutableSpi>> cachedByNode,
            JSqlClientImplementor sqlClient
    ) {
        boolean needsCache = node.isCacheableNode(sqlClient) && hasMissingLeaves(fresh, node);
        if (needsCache && cached == null) {
            return false;
        }
        if (cached != null) {
            if (cached.__type() != fresh.__type()) {
                return false;
            }
            for (ImmutableProp leaf : node.leaves) {
                if (fresh.__isLoaded(leaf.getId())) {
                    continue;
                }
                if (!cached.__isLoaded(leaf.getId())) {
                    return false;
                }
            }
        }
        for (Map.Entry<ImmutableProp, CacheContentMask> e : node.children.entrySet()) {
            ImmutableProp prop = e.getKey();
            if (!fresh.__isLoaded(prop.getId())) {
                continue;
            }
            Object child = fresh.__get(prop.getId());
            if (child == null) {
                continue;
            }
            CacheContentMask childNode = e.getValue();
            if (childNode.embedded) {
                ImmutableSpi cachedChild = cached != null && cached.__isLoaded(prop.getId()) ?
                        (ImmutableSpi) cached.__get(prop.getId()) : null;
                if (!validateNode((ImmutableSpi) child, childNode, cachedChild, cachedByNode, sqlClient)) {
                    return false;
                }
            } else if (!validate((ImmutableSpi) child, childNode, cachedByNode, sqlClient)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasMissingLeaves(ImmutableSpi fresh, CacheContentMask node) {
        for (ImmutableProp leaf : node.leaves) {
            if (!fresh.__isLoaded(leaf.getId())) {
                return true;
            }
        }
        for (Map.Entry<ImmutableProp, CacheContentMask> e : node.children.entrySet()) {
            // A navigated entity child owns its own cache node and is validated
            // separately; only an embedded child is part of this node's payload.
            if (!e.getValue().embedded) {
                continue;
            }
            ImmutableProp prop = e.getKey();
            if (!fresh.__isLoaded(prop.getId())) {
                continue;
            }
            Object child = fresh.__get(prop.getId());
            if (child == null) {
                continue;
            }
            if (hasMissingLeaves((ImmutableSpi) child, e.getValue())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies only the cacheable approved leaves that SQL did not load into the fresh
     * value, preserving every fresh scalar, edge and concrete type. Embedded values are
     * merged leaf-by-leaf so unapproved siblings stay fresh. The cache payload is never
     * merged as a whole. Finally each approved leaf's and formula's serialization
     * visibility is restored from the original projection fetcher: a JVM formula's
     * implicit input stays hidden, but a dependency the projection selected explicitly
     * stays visible, and the formula itself stays visible.
     */
    static ImmutableSpi overlay(
            ImmutableSpi fresh,
            Fetcher<?> projection,
            CacheContentMask node,
            Map<CacheContentMask, Map<Object, ImmutableSpi>> cachedByNode,
            JSqlClientImplementor sqlClient
    ) {
        ImmutableSpi cached = cachedOf(fresh, node, cachedByNode, sqlClient);
        return (ImmutableSpi) Internal.produce(
                fresh.__type(),
                fresh,
                true,
                draft -> overlayNode((DraftSpi) draft, fresh, projection, node, cached, cachedByNode, sqlClient)
        );
    }

    private static void overlayNode(
            DraftSpi draft,
            ImmutableSpi fresh,
            Fetcher<?> projection,
            CacheContentMask node,
            ImmutableSpi cached,
            Map<CacheContentMask, Map<Object, ImmutableSpi>> cachedByNode,
            JSqlClientImplementor sqlClient
    ) {
        if (cached != null) {
            for (ImmutableProp leaf : node.leaves) {
                if (fresh.__isLoaded(leaf.getId())) {
                    // SQL wins any overlap, including a fresh formula dependency input.
                    continue;
                }
                draft.__set(leaf.getId(), cached.__get(leaf.getId()));
            }
        }
        // Visibility is a property of the original projection, not of the cache mask: a
        // stored input the mask approves because a JVM formula needs it is hidden only
        // when the projection itself left it implicit. Applying both directions keeps a
        // projection that also selected the input explicitly visible, while a truly
        // implicit dependency (re-derived in the retained fetcher) is re-hidden.
        for (ImmutableProp leaf : node.leaves) {
            restoreVisibility(draft, projection, leaf);
        }
        for (ImmutableProp formula : node.formulas) {
            restoreVisibility(draft, projection, formula);
        }
        for (Map.Entry<ImmutableProp, CacheContentMask> e : node.children.entrySet()) {
            ImmutableProp prop = e.getKey();
            if (!fresh.__isLoaded(prop.getId())) {
                continue;
            }
            Object child = fresh.__get(prop.getId());
            if (child == null) {
                continue;
            }
            CacheContentMask childNode = e.getValue();
            Fetcher<?> childProjection = childProjection(projection, prop);
            if (childNode.embedded) {
                ImmutableSpi cachedChild = cached != null && cached.__isLoaded(prop.getId()) ?
                        (ImmutableSpi) cached.__get(prop.getId()) : null;
                draft.__set(
                        prop.getId(),
                        overlayEmbedded(
                                (ImmutableSpi) child, childProjection, childNode, cachedChild, cachedByNode, sqlClient
                        )
                );
            } else {
                draft.__set(
                        prop.getId(),
                        overlay((ImmutableSpi) child, childProjection, childNode, cachedByNode, sqlClient)
                );
            }
        }
    }

    private static void restoreVisibility(DraftSpi draft, Fetcher<?> projection, ImmutableProp prop) {
        Field field = projection != null ? projection.getFieldMap().get(prop.getName()) : null;
        if (field != null) {
            draft.__show(prop.getId(), !field.isImplicit());
        }
    }

    private static Fetcher<?> childProjection(Fetcher<?> projection, ImmutableProp prop) {
        Field field = projection != null ? projection.getFieldMap().get(prop.getName()) : null;
        return field != null ? field.getChildFetcher() : null;
    }

    private static ImmutableSpi overlayEmbedded(
            ImmutableSpi fresh,
            Fetcher<?> projection,
            CacheContentMask node,
            ImmutableSpi cached,
            Map<CacheContentMask, Map<Object, ImmutableSpi>> cachedByNode,
            JSqlClientImplementor sqlClient
    ) {
        return (ImmutableSpi) Internal.produce(
                fresh.__type(),
                fresh,
                true,
                draft -> overlayNode((DraftSpi) draft, fresh, projection, node, cached, cachedByNode, sqlClient)
        );
    }

    private static ImmutableSpi cachedOf(
            ImmutableSpi fresh,
            CacheContentMask node,
            Map<CacheContentMask, Map<Object, ImmutableSpi>> cachedByNode,
            JSqlClientImplementor sqlClient
    ) {
        if (node.embedded || !node.hasCacheableOwnLeaves(sqlClient)) {
            return null;
        }
        Map<Object, ImmutableSpi> map = cachedByNode.get(node);
        if (map == null) {
            return null;
        }
        Object id = fresh.__get(node.type.getIdProp().getId());
        return id != null ? map.get(id) : null;
    }
}
