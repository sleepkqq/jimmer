package org.babyfish.jimmer.sql.ast.query.specification;

import org.babyfish.jimmer.client.ApiIgnore;
import org.babyfish.jimmer.sql.ast.table.spi.TableLike;

/**
 * Adds user-defined predicates to a generated specification.
 *
 * <p>A specification DTO that implements this interface, either directly or through
 * an intermediate user interface, has its generated {@code applyTo} invoke
 * {@link #applyExtension(SpecificationArgs)} exactly once, after all predicates
 * derived from entity properties. The supplied {@code args} is positioned at the
 * specification's own root table, so predicates added by
 * {@link SpecificationArgs#where(org.babyfish.jimmer.sql.ast.Predicate...)} participate
 * in the containing {@link JSpecification#and}/{@link JSpecification#or} composition.</p>
 *
 * <p>Intermediate user interfaces must provide a default {@code applyExtension}
 * implementation, because the generated DTO cannot implement arbitrary methods.
 * A specification whose base type is an embeddable cannot support this interface:
 * its {@code applyTo} only receives a {@code PredicateApplier}, so generation fails.</p>
 *
 * @param <E> The entity type queried by the specification
 * @param <T> The table type of {@code E}
 */
@ApiIgnore
public interface JSpecificationExtension<E, T extends TableLike<E>> {

    /**
     * Applies predicates that are not derived from entity properties.
     *
     * @param args The root arguments of the specification being applied
     */
    void applyExtension(SpecificationArgs<E, T> args);
}
