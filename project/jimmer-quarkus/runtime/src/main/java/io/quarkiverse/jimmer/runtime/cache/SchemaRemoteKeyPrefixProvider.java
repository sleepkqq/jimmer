package io.quarkiverse.jimmer.runtime.cache;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.Table;
import org.babyfish.jimmer.sql.cache.RemoteKeyPrefixProvider;
import org.jetbrains.annotations.Nullable;

/**
 * Prefixes Redis cache keys with the entity's database schema — the schema is the natural
 * per-service namespace when several microservices share one Redis (like PG schemas separate
 * their tables). Resolution order: {@link Table#schema()}, then a schema embedded in
 * {@link Table#name()} ({@code "schema.table"}), then the datasource's
 * {@code quarkus.jimmer.default-schema}. Entities without any schema keep Jimmer's default
 * unprefixed keys.
 */
public class SchemaRemoteKeyPrefixProvider implements RemoteKeyPrefixProvider {

    private final String defaultSchema;

    public SchemaRemoteKeyPrefixProvider(@Nullable String defaultSchema) {
        this.defaultSchema = defaultSchema == null ? "" : defaultSchema;
    }

    @Override
    public String typeKeyPrefix(ImmutableType type) {
        return schemaPrefix(type) + RemoteKeyPrefixProvider.super.typeKeyPrefix(type);
    }

    @Override
    public String propKeyPrefix(ImmutableProp prop) {
        return schemaPrefix(prop.getDeclaringType()) + RemoteKeyPrefixProvider.super.propKeyPrefix(prop);
    }

    private String schemaPrefix(ImmutableType type) {
        String schema = schemaOf(type);
        return schema.isEmpty() ? "" : schema + ':';
    }

    private String schemaOf(ImmutableType type) {
        Table table = type.getJavaClass().getAnnotation(Table.class);
        if (table != null) {
            if (!table.schema().isEmpty()) {
                return table.schema();
            }
            int dot = table.name().lastIndexOf('.');
            if (dot > 0) {
                return table.name().substring(0, dot);
            }
        }
        return defaultSchema;
    }
}
