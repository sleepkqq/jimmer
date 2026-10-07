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
import org.babyfish.jimmer.sql.fetcher.impl.FetcherImplementor;
import org.babyfish.jimmer.sql.fetcher.impl.JoinFetchFieldVisitor;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive, path-specific whitelist of object-cache content for
 * {@link ObjectCacheQueryExecution}. Only stored scalars/embedded leaves and the stored
 * dependency closure of an approved JVM formula are served from the owning entity's
 * object cache; associations navigate into child content and never authorize the edge.
 * Collections, id-views, remote and SQL formulas are rejected, as are optimistic-lock
 * and logical-delete properties. {@link #retainedFetcher} removes approved leaves from
 * the SQL projection (a whole or nullable embedded is never reduced to zero columns,
 * which would fabricate absence) and {@link #overlay} fills exactly those leaves from
 * the cached payload. A single-table fetcher's {@code forType(Subtype, ...)} branches
 * are child nodes keyed by branch type, sharing the declaring entity's cache owner.
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

    /** STI type branches of this entity, keyed by branch type; empty when non-polymorphic. */
    private final Map<ImmutableType, CacheContentMask> typeBranches;

    private CacheContentMask(
            ImmutableType type,
            boolean embedded,
            boolean nullable,
            ImmutableType cacheOwnerType,
            Map<ImmutableProp, CacheContentMask> children,
            Set<ImmutableProp> leaves,
            Set<ImmutableProp> formulas,
            Map<ImmutableType, CacheContentMask> typeBranches
    ) {
        this.type = type;
        this.embedded = embedded;
        this.nullable = nullable;
        this.cacheOwnerType = cacheOwnerType;
        this.children = children;
        this.leaves = leaves;
        this.formulas = formulas;
        this.typeBranches = typeBranches;
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
        ImmutableType cacheOwnerType = ownerType != null ? ownerType : type;
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
                // A JVM formula is removed from SQL and recomputed by its getter. The
                // native fetcher already expands its stored dependency closure into the
                // field map, so those inputs are approved below as ordinary leaves.
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
        // STI subtype content lives in native branches, not the field map; each is a
        // child node sharing this entity's cache owner.
        Map<ImmutableType, CacheContentMask> typeBranches = new LinkedHashMap<>();
        for (Map.Entry<ImmutableType, Fetcher<?>> e : typeBranchFetchers(fetcher).entrySet()) {
            typeBranches.put(e.getKey(), of(e.getValue(), false, false, cacheOwnerType));
        }
        return new CacheContentMask(
                type,
                embedded,
                nullable,
                cacheOwnerType,
                children,
                leaves,
                formulas,
                typeBranches
        );
    }

    /**
     * A copy with all navigated children and branches dropped, keeping only this node's
     * own leaves (for a bare entity-table selection whose partial form cannot express
     * association content selected by other tuple slots).
     */
    CacheContentMask withoutChildren() {
        return new CacheContentMask(
                type,
                embedded,
                nullable,
                cacheOwnerType,
                new LinkedHashMap<>(),
                leaves,
                formulas,
                new LinkedHashMap<>()
        );
    }

    CacheContentMask selectedBy(Fetcher<?> projection, JSqlClientImplementor sqlClient) {
        Fetcher<?> selected = FetcherFactory.filterExpanded(
                projection,
                (type, prop, path) -> isRemovedAt(sqlClient, type, path, prop)
        );
        return selected != null ? of(selected) : null;
    }

    /**
     * The SQL projection with every approved leaf of a cacheable node removed; associations
     * stay as edges. Returns {@code null} when reducing would drop a proving join, erase a
     * field-local filter, corrupt native recursion, or hit an unsupported mask shape.
     *
     * @throws IllegalArgumentException when the mask does not match the selected fetcher
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
        if (!isSubset(projection, Collections.<String, Field>emptyMap(), mask)) {
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
        if (hasJoinedNestedMask(projection, mask, sqlClient)) {
            return null;
        }
        if (hasReducedFieldLocalFilter(projection, mask)) {
            return null;
        }
        if (hasInheritedBranchChild(mask)) {
            return null;
        }
        // Filter the native-expanded field map, not the declaration chain: the stored
        // dependency containers of a removed JVM formula only exist in the expanded map,
        // so a chain filter would drop them and leave the formula getter unloaded. The
        // predicate is scoped to the current fetcher's type, so a branch approval is
        // judged in that branch's scope, not the root's.
        return FetcherFactory.filterExpanded(
                projection,
                (type, prop, path) -> isPrefixOfAny(keepEmbedded, path) ||
                        !mask.isRemovedAt(sqlClient, type, path, prop)
        );
    }

    // ponytail: decline a branch approving an ancestor-declared child; refine to a parent-type proof if this over-declines.
    private static boolean hasInheritedBranchChild(CacheContentMask node) {
        for (CacheContentMask branch : node.typeBranches.values()) {
            for (ImmutableProp childProp : branch.children.keySet()) {
                if (childProp.toOriginal().getDeclaringType() != branch.type) {
                    return true;
                }
            }
            if (hasInheritedBranchChild(branch)) {
                return true;
            }
        }
        for (CacheContentMask child : node.children.values()) {
            if (hasInheritedBranchChild(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether reducing an approved reference that carries a field-local filter to an id-only
     * edge would erase its admission boundary; such a hint is declined.
     */
    private static boolean hasReducedFieldLocalFilter(Fetcher<?> projection, CacheContentMask node) {
        for (Field field : projection.getFieldMap().values()) {
            ImmutableProp prop = field.getProp();
            if (prop.isId() || prop.isDiscriminator()) {
                continue;
            }
            CacheContentMask childNode = childOf(node.children, prop);
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
        for (Map.Entry<ImmutableType, CacheContentMask> e : node.typeBranches.entrySet()) {
            Fetcher<?> branchFetcher = branchProjection(projection, e.getKey());
            if (branchFetcher != null && hasReducedFieldLocalFilter(branchFetcher, e.getValue())) {
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
        for (Fetcher<?> branch : typeBranchFetchers(projection).values()) {
            if (hasRecursiveProjection(branch)) {
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
     * Records embedded paths whose every selected leaf would be removed, so the retained
     * fetcher keeps their SQL columns instead of fabricating absence. A nullable embedded's
     * own leaves are never removed, so it is recorded only through this nested protection.
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
                // Descend through a nullable embedded too: a required embedded nested below
                // it can still lose every leaf, which must keep its columns.
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
        // A branch shares the declaring entity's path, so its embedded values use the
        // same root mask and relative path.
        for (Fetcher<?> branch : typeBranchFetchers(fetcher).values()) {
            collectZeroingEmbedded(branch, rootMask, path, sqlClient, out);
        }
    }

    /**
     * Whether every approved mask entry is selected by the projection. A branch also
     * inherits its declaring entity's selected fields, so an inherited base leaf counts for
     * the branch even when the branch fetcher does not repeat it locally. A branch whose
     * content is selected nowhere (no projection branch and not inherited) is still
     * rejected, so a mask cannot introduce unselected properties.
     */
    private static boolean isSubset(Fetcher<?> projection, Map<String, Field> inherited, CacheContentMask mask) {
        return isSubset(
                projection.getFieldMap(),
                typeBranchFetchers(projection),
                inherited,
                mask
        );
    }

    private static boolean isSubset(
            Map<String, Field> fieldMap,
            Map<ImmutableType, Fetcher<?>> branches,
            Map<String, Field> inherited,
            CacheContentMask mask
    ) {
        for (ImmutableProp leaf : mask.leaves) {
            if (!selected(fieldMap, inherited, leaf)) {
                return false;
            }
        }
        for (ImmutableProp formula : mask.formulas) {
            if (!selected(fieldMap, inherited, formula)) {
                return false;
            }
        }
        for (Map.Entry<ImmutableProp, CacheContentMask> e : mask.children.entrySet()) {
            Field field = fieldOf(fieldMap, inherited, e.getKey().getName());
            if (field == null || field.getChildFetcher() == null) {
                return false;
            }
            if (!isSubset(field.getChildFetcher(), Collections.<String, Field>emptyMap(), e.getValue())) {
                return false;
            }
        }
        for (Map.Entry<ImmutableType, CacheContentMask> e : mask.typeBranches.entrySet()) {
            Fetcher<?> branchFetcher = branches.get(e.getKey());
            // An absent projection branch inherits only the declaring entity's selection.
            Map<String, Field> branchFields = branchFetcher != null ?
                    branchFetcher.getFieldMap() :
                    Collections.<String, Field>emptyMap();
            Map<ImmutableType, Fetcher<?>> branchBranches = branchFetcher != null ?
                    typeBranchFetchers(branchFetcher) :
                    Collections.<ImmutableType, Fetcher<?>>emptyMap();
            if (!isSubset(branchFields, branchBranches, mergedFields(fieldMap, inherited), e.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean selected(Map<String, Field> fieldMap, Map<String, Field> inherited, ImmutableProp prop) {
        return fieldMap.containsKey(prop.getName()) || inherited.containsKey(prop.getName());
    }

    private static Field fieldOf(Map<String, Field> fieldMap, Map<String, Field> inherited, String name) {
        Field field = fieldMap.get(name);
        return field != null ? field : inherited.get(name);
    }

    private static Map<String, Field> mergedFields(Map<String, Field> fieldMap, Map<String, Field> inherited) {
        if (inherited.isEmpty()) {
            return fieldMap;
        }
        Map<String, Field> merged = new LinkedHashMap<>(inherited);
        merged.putAll(fieldMap);
        return merged;
    }

    /** A subtype's inherited property wrapper denotes the same underlying property as its {@link ImmutableProp#toOriginal()}. */
    private static boolean containsProp(Set<ImmutableProp> props, ImmutableProp prop) {
        if (props.contains(prop)) {
            return true;
        }
        ImmutableProp original = prop.toOriginal();
        for (ImmutableProp p : props) {
            if (p.toOriginal() == original) {
                return true;
            }
        }
        return false;
    }

    private static CacheContentMask childOf(Map<ImmutableProp, CacheContentMask> children, ImmutableProp prop) {
        CacheContentMask child = children.get(prop);
        if (child != null) {
            return child;
        }
        ImmutableProp original = prop.toOriginal();
        for (Map.Entry<ImmutableProp, CacheContentMask> e : children.entrySet()) {
            if (e.getKey().toOriginal() == original) {
                return e.getValue();
            }
        }
        return null;
    }

    private boolean isRemovedAt(
            JSqlClientImplementor sqlClient,
            ImmutableType currentType,
            List<ImmutableProp> path,
            ImmutableProp prop
    ) {
        CacheContentMask node = this;
        for (ImmutableProp step : path) {
            node = node.childFor(step);
            if (node == null) {
                return false;
            }
        }
        if (node.embedded && node.nullable) {
            // A nullable embedded's presence depends on all of its columns; removing any
            // could fabricate absence.
            return false;
        }
        if (!node.isCacheableNode(sqlClient)) {
            return false;
        }
        if (node.hasWholeEmbeddedLeaf(prop)) {
            // Whole-embedded leaves carry no nested loadedness; keep their columns.
            return false;
        }
        // A node-level (base) approval is common to every concrete type and every
        // applicable branch, so it removes the column in any fetch scope.
        if (containsProp(node.leaves, prop)) {
            return true;
        }
        if (containsProp(node.formulas, prop)) {
            return prop.getSqlTemplate() == null;
        }
        // Otherwise the base and every applicable branch must together approve the property
        // for every concrete type in the current fetcher's scope. A sibling branch's
        // approval is never inherited, so an unapproved sibling stays SQL-fresh.
        return node.approvesEveryCarrier(currentType, prop);
    }

    /** Whether this node or a subtype branch approves {@code prop} as a whole-embedded leaf. */
    private boolean hasWholeEmbeddedLeaf(ImmutableProp prop) {
        if (containsProp(leaves, prop) && prop.isEmbedded(EmbeddedLevel.SCALAR)) {
            return true;
        }
        for (CacheContentMask branch : typeBranches.values()) {
            if (branch.hasWholeEmbeddedLeaf(prop)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether every concrete type in {@code scopeType} that can carry {@code prop} approves
     * it, by this node or a branch assignable from that type (never a sibling union).
     */
    private boolean approvesEveryCarrier(ImmutableType scopeType, ImmutableProp prop) {
        InheritanceInfo inheritanceInfo = scopeType.getInheritanceInfo();
        Collection<ImmutableType> concreteTypes = inheritanceInfo != null ?
                inheritanceInfo.getConcreteTypes(scopeType) :
                Collections.<ImmutableType>singleton(scopeType);
        boolean any = false;
        for (ImmutableType concreteType : concreteTypes) {
            if (!prop.toOriginal().getDeclaringType().isAssignableFrom(concreteType)) {
                continue;
            }
            any = true;
            if (!approvesForConcreteType(concreteType, prop)) {
                return false;
            }
        }
        return any;
    }

    private boolean approvesForConcreteType(ImmutableType concreteType, ImmutableProp prop) {
        if (containsProp(leaves, prop) || containsProp(formulas, prop)) {
            return true;
        }
        for (CacheContentMask branch : typeBranches.values()) {
            if (branch.type.isAssignableFrom(concreteType) &&
                    branch.approvesForConcreteType(concreteType, prop)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a path step through this node's children, then through its subtype branches.
     * When more than one branch can reach the step the owning scope is ambiguous, so the
     * caller keeps the column fresh rather than trusting a first match.
     */
    private CacheContentMask childFor(ImmutableProp step) {
        CacheContentMask child = childOf(children, step);
        if (child != null) {
            return child;
        }
        CacheContentMask found = null;
        for (CacheContentMask branch : typeBranches.values()) {
            CacheContentMask candidate = branch.childFor(step);
            if (candidate != null) {
                if (found != null) {
                    return null;
                }
                found = candidate;
            }
        }
        return found;
    }

    /** Native type branches of a fetcher, or empty for a non-native fetcher. */
    private static Map<ImmutableType, Fetcher<?>> typeBranchFetchers(Fetcher<?> fetcher) {
        if (fetcher instanceof FetcherImplementor<?>) {
            return ((FetcherImplementor<?>) fetcher).__getTypeBranchFetcherMap();
        }
        return Collections.emptyMap();
    }

    /**
     * Subtype branches whose type is assignable from {@code concreteType}, mirroring
     * {@code ObjectReader.TypeBranchReader}; non-matching branches stay SQL-fresh.
     */
    private static List<CacheContentMask> applicableBranches(CacheContentMask node, ImmutableType concreteType) {
        if (node.typeBranches.isEmpty()) {
            return Collections.emptyList();
        }
        List<CacheContentMask> result = new ArrayList<>();
        for (CacheContentMask branch : node.typeBranches.values()) {
            if (branch.type.isAssignableFrom(concreteType)) {
                result.add(branch);
            }
        }
        return result;
    }

    /** The projection fetcher for a subtype branch, or null when the projection misses it. */
    private static Fetcher<?> branchProjection(Fetcher<?> projection, ImmutableType branchType) {
        return typeBranchFetchers(projection).get(branchType);
    }

    /** Whether this node's own approved leaves can be served from a cache. */
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
        for (CacheContentMask branch : typeBranches.values()) {
            if (branch.hasStoredLeaves()) {
                return true;
            }
        }
        return false;
    }

    /** Whether this entity node's approved content (including embedded leaves) is cached. */
    boolean hasCacheableOwnLeaves(JSqlClientImplementor sqlClient) {
        return !embedded && isCacheableNode(sqlClient) && hasStoredLeaves();
    }

    boolean needsCache(ImmutableSpi fresh, JSqlClientImplementor sqlClient) {
        return isCacheableNode(sqlClient) && hasMissingLeaves(fresh, this);
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
        for (CacheContentMask branch : typeBranches.values()) {
            if (branch.hasCacheableLeaves(sqlClient)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any cacheable node uses JOINED inheritance, whose partial projection needs
     * per-branch physical joins; such a hint declines. SINGLE_TABLE stays supported.
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
        for (CacheContentMask branch : typeBranches.values()) {
            if (branch.hasJoinedInheritanceCacheable(sqlClient)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Declines a whitelisted nested association whose reduction to id-only would drop an
     * effective join that proves the target exists. Only a fully removed FAKE-FK join
     * declines; the decision uses the original (cache-enabled) client's join semantics.
     */
    private static boolean hasJoinedNestedMask(
            Fetcher<?> projection,
            CacheContentMask node,
            JSqlClientImplementor sqlClient
    ) {
        if (hasJoinedNestedChildren(projection, node, sqlClient)) {
            return true;
        }
        // A branch shares the declaring entity's path; check its associations from the branch.
        for (CacheContentMask branch : node.typeBranches.values()) {
            Fetcher<?> branchFetcher = branchProjection(projection, branch.type);
            if (branchFetcher != null && hasJoinedNestedMask(branchFetcher, branch, sqlClient)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasJoinedNestedChildren(
            Fetcher<?> projection,
            CacheContentMask node,
            JSqlClientImplementor sqlClient
    ) {
        for (Map.Entry<ImmutableProp, CacheContentMask> e : node.children.entrySet()) {
            ImmutableProp prop = e.getKey();
            CacheContentMask childNode = e.getValue();
            if (childNode.embedded) {
                continue;
            }
            Field field = projection.getFieldMap().get(prop.getName());
            if (field == null || field.getChildFetcher() == null) {
                continue;
            }
            // The path is relative to {@code node}; the recursive call rebases its mask,
            // so it must not carry this node's path forward.
            List<ImmutableProp> childPath = new ArrayList<>();
            childPath.add(prop);
            boolean fakeUnfiltered =
                    !prop.isTargetForeignKeyReal(sqlClient.getMetadataStrategy()) &&
                            sqlClient.getFilters().getTargetFilter(prop) == null;
            if (fakeUnfiltered &&
                    JoinFetchFieldVisitor.isJoinField(field, sqlClient) &&
                    isContentFullyRemoved(field.getChildFetcher(), childPath, node, sqlClient)) {
                return true;
            }
            if (hasJoinedNestedMask(field.getChildFetcher(), childNode, sqlClient)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether every selectable field is removed, so the retained fetcher would collapse to
     * an id-only reference and lose the join/existence proof.
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
            if (!rootMask.isRemovedAt(sqlClient, fetcher.getImmutableType(), path, prop)) {
                allRemoved = false;
            }
        }
        for (Fetcher<?> branch : typeBranchFetchers(fetcher).values()) {
            if (!hasSelectableContent(branch)) {
                continue;
            }
            any = true;
            if (!isContentFullyRemoved(branch, path, rootMask, sqlClient)) {
                allRemoved = false;
            }
        }
        return any && allRemoved;
    }

    /** Whether a fetcher, through its fields or any branch, selects any content. */
    private static boolean hasSelectableContent(Fetcher<?> fetcher) {
        for (Field field : fetcher.getFieldMap().values()) {
            ImmutableProp prop = field.getProp();
            if (!prop.isId() && !prop.isDiscriminator()) {
                return true;
            }
        }
        for (Fetcher<?> branch : typeBranchFetchers(fetcher).values()) {
            if (hasSelectableContent(branch)) {
                return true;
            }
        }
        return false;
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
     * Walks the fresh retained graph and records, per cacheable node, the id-to-concrete-type
     * group to load from its cache. An id is proven only when the fresh read establishes that
     * exact id and concrete type; other ids keep their fresh per-id existence read so a FAKE
     * FK can never resurrect a warm but deleted target.
     *
     * @return {@code false} when a navigated target's concrete type is not established
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
            if (!childNode.embedded && childNode.needsCache(childSpi, sqlClient)) {
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
        // Only the branch matching the fresh concrete type applies, sharing this entity's
        // payload; its navigated children are seeded from the same fresh row.
        for (CacheContentMask branch : applicableBranches(node, fresh.__type())) {
            if (!collectSeeds(fresh, branch, seeds, provenSeeds, sqlClient)) {
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
     * Whether the fresh value proves its own concrete type: exact for a non-polymorphic
     * type, and for an inheritance hierarchy only when its discriminator was read (an
     * id-only FK reader must not be accepted as subtype proof).
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
     * counterpart and every navigated edge matches its cached target by concrete type and
     * id; {@code false} makes the caller fall back to the whole original query once.
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
        if (node.needsCache(fresh, sqlClient) && cached == null) {
            return false;
        }
        return validateContent(fresh, node, cached, cachedByNode, sqlClient);
    }

    private static boolean validateContent(
            ImmutableSpi fresh,
            CacheContentMask node,
            ImmutableSpi cached,
            Map<CacheContentMask, Map<Object, ImmutableSpi>> cachedByNode,
            JSqlClientImplementor sqlClient
    ) {
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
        // A branch shares this entity's payload, so its leaves validate against the same
        // cached concrete-type value.
        for (CacheContentMask branch : applicableBranches(node, fresh.__type())) {
            if (!validateContent(fresh, branch, cached, cachedByNode, sqlClient)) {
                return false;
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
        for (CacheContentMask branch : applicableBranches(node, fresh.__type())) {
            if (hasMissingLeaves(fresh, branch)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies only the approved leaves SQL did not load into the fresh value (SQL wins
     * overlaps), merging embedded values leaf-by-leaf and never the cache payload as a
     * whole. Approved leaves'/formulas' serialization visibility is restored from the
     * original projection, so explicit selections stay visible and implicit ones hidden.
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
                draft -> overlayContent(
                        (DraftSpi) draft, fresh, projection, projection, node, cached, cachedByNode, sqlClient
                )
        );
    }

    /**
     * {@code entityProjection} is the whole fetcher for this entity (base plus branches),
     * used for visibility; {@code nodeProjection} is the current base or branch fetcher,
     * used to descend. Visibility follows the native explicit-over-implicit rule across all
     * applicable branches, so a branch's implicit field cannot hide a base explicit one.
     */
    private static void overlayContent(
            DraftSpi draft,
            ImmutableSpi fresh,
            Fetcher<?> entityProjection,
            Fetcher<?> nodeProjection,
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
        for (ImmutableProp leaf : node.leaves) {
            restoreVisibility(draft, entityProjection, fresh.__type(), leaf);
        }
        for (ImmutableProp formula : node.formulas) {
            restoreVisibility(draft, entityProjection, fresh.__type(), formula);
        }
        // A branch shares this payload/draft; overlay from the same cached value through
        // its own projection while visibility stays scoped to the whole entity.
        for (CacheContentMask branch : applicableBranches(node, fresh.__type())) {
            // A mask branch with no projection counterpart inherits the current selection,
            // so its (inherited) children are descended through the same projection.
            Fetcher<?> branchProjection = branchProjection(nodeProjection, branch.type);
            overlayContent(
                    draft,
                    fresh,
                    entityProjection,
                    branchProjection != null ? branchProjection : nodeProjection,
                    branch,
                    cached,
                    cachedByNode,
                    sqlClient
            );
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
            Fetcher<?> childProjection = childProjection(nodeProjection, prop);
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

    /**
     * Restores a leaf's or formula's serialization visibility from the whole entity
     * projection: explicit wins over implicit across the base and every applicable branch;
     * a property absent from that projection is left untouched.
     */
    private static void restoreVisibility(
            DraftSpi draft,
            Fetcher<?> entityProjection,
            ImmutableType concreteType,
            ImmutableProp prop
    ) {
        Boolean explicit = explicitSelection(entityProjection, concreteType, prop);
        if (explicit != null) {
            draft.__show(prop.getId(), explicit);
        }
    }

    private static Boolean explicitSelection(Fetcher<?> projection, ImmutableType concreteType, ImmutableProp prop) {
        Boolean result = null;
        Field field = projection.getFieldMap().get(prop.getName());
        if (field != null) {
            result = !field.isImplicit();
        }
        for (Map.Entry<ImmutableType, Fetcher<?>> e : typeBranchFetchers(projection).entrySet()) {
            if (!e.getKey().isAssignableFrom(concreteType)) {
                continue;
            }
            Boolean branch = explicitSelection(e.getValue(), concreteType, prop);
            if (Boolean.TRUE.equals(branch)) {
                return Boolean.TRUE;
            }
            if (Boolean.FALSE.equals(branch) && result == null) {
                result = Boolean.FALSE;
            }
        }
        return result;
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
                draft -> overlayContent(
                        (DraftSpi) draft, fresh, projection, projection, node, cached, cachedByNode, sqlClient
                )
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
