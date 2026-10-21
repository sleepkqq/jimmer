package io.quarkiverse.jimmer.runtime.cache;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;

import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.CacheAdmissionPolicy;

/**
 * Installs the optional application cache admission policy.
 *
 * <p>A policy is usually request scoped. When the request scope is not active the policy
 * cannot answer, so its cache admission is denied instead of admitting command content.</p>
 */
final class AdmissionPolicies {

    private AdmissionPolicies() {
    }

    static CacheAdmissionPolicy resolve(Instance<CacheAdmissionPolicy> instance) {
        if (!instance.isResolvable()) {
            return null;
        }
        CacheAdmissionPolicy policy = instance.get();
        return new CacheAdmissionPolicy() {
            @Override
            public boolean isAdmissible(ImmutableType type) {
                try {
                    return policy.isAdmissible(type);
                } catch (ContextNotActiveException ex) {
                    return false;
                }
            }

            @Override
            public boolean isAdmissible(ImmutableType type, Object row) {
                try {
                    return policy.isAdmissible(type, row);
                } catch (ContextNotActiveException ex) {
                    return false;
                }
            }
        };
    }
}
