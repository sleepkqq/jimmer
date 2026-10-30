package org.babyfish.jimmer.sql.runtime;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;

import java.sql.Connection;
import java.util.Collection;

/**
 * Receives the keys of the rows mutated by the SQL statements Jimmer executes.
 * <p>
 * It is only consulted when the trigger type of the {@link org.babyfish.jimmer.sql.JSqlClient} is
 * {@link org.babyfish.jimmer.sql.event.TriggerType#BINLOG_ONLY}: in that mode Jimmer never fires
 * transaction-level triggers, so a cache cannot learn about a mutation from
 * {@link org.babyfish.jimmer.sql.event.Triggers}. The implementation is expected to be the
 * {@link ConnectionManager} of that client, so that it can evict the affected caches after the
 * transaction of the connection has committed.
 * <p>
 * Both methods are invoked after the corresponding SQL statement has been executed on
 * {@code con}. The collections are the keys Jimmer knows about: an update or a delete
 * pre-selects them by id from the mutation target, so a key the statement did not accept may
 * still be reported. Implementations must tolerate unknown keys and must not throw.
 */
public interface MutationKeyListener {

    /**
     * Called after rows of {@code type} have been inserted, updated or upserted.
     *
     * @param con  The connection the statement was executed on.
     * @param type The concrete type of the mutated entities.
     * @param ids  The ids of the mutated entities, never empty.
     */
    void entitiesMutated(Connection con, ImmutableType type, Collection<?> ids);

    /**
     * Called after the rows of an association property have been inserted or deleted.
     *
     * @param con       The connection the statement was executed on.
     * @param prop      The mutated association property.
     * @param sourceIds The ids of the source entities, never empty.
     */
    void associationsMutated(Connection con, ImmutableProp prop, Collection<?> sourceIds);
}
