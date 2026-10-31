package org.babyfish.jimmer.sql.kt.ast.query.specification

import org.babyfish.jimmer.client.ApiIgnore

/**
 * Adds user-defined predicates to a generated specification.
 *
 * A specification DTO that implements this interface, either directly or through
 * an intermediate user interface, has its generated `applyTo` invoke
 * [applyExtension] exactly once, after all predicates derived from entity
 * properties. The supplied [args] is positioned at the specification's own root
 * table, so predicates added by [KSpecificationArgs.where] participate in the
 * containing [KSpecification.and]/[KSpecification.or]/[KSpecification.not]
 * composition.
 *
 * Intermediate user interfaces must provide a default [applyExtension]
 * implementation, because the generated DTO cannot implement arbitrary methods.
 * A specification whose base type is an embeddable cannot support this interface:
 * its `applyTo` only receives a `PredicateApplier`, so generation fails.
 *
 * @param E The entity type queried by the specification
 */
@ApiIgnore
interface KSpecificationExtension<E : Any> {

    /**
     * Applies predicates that are not derived from entity properties.
     */
    fun applyExtension(args: KSpecificationArgs<E>)
}
