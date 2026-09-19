package io.quarkiverse.jimmer.it.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.jimmer.it.Constant;
import io.quarkiverse.jimmer.it.entity.BookStore;
import io.quarkiverse.jimmer.it.entity.UserRole;
import io.quarkiverse.jimmer.it.repository.BookStoreRepository;
import io.quarkiverse.jimmer.it.repository.UserRoleRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Regression tests for two correctness bugs in QuarkusConnectionManager.
 *
 * <h3>Bug 1 — cross-datasource connection bleed (static ThreadLocal)</h3>
 * The former static TX_CONNECTION was shared across all manager instances.
 * In a thread that already cached a default-DS connection, a subsequent
 * DB2 repository call reused that connection instead of opening its own.
 * User-role SQL then executed against the default datasource where the
 * table does not exist → silent data corruption or PSQLException.
 *
 * <h3>Bug 2 — REQUIRES_NEW / NOT_SUPPORTED reuses suspended TX's connection</h3>
 * Suspending a transaction does not fire afterCompletion, so the ThreadLocal
 * retained the outer transaction's connection. The inner TX reused a connection
 * still enlisted in the suspended outer TX → Narayana: "Enlisted connection used
 * without active transaction".
 *
 * <h3>Fix</h3>
 * Replace static ThreadLocal with a per-instance Object key stored via
 * TransactionSynchronizationRegistry.putResource/getResource.
 * Resources are scoped to (manager instance × current JTA transaction):
 * different datasources use different keys (Bug 1), and suspended transactions
 * have a different TSR context than the new inner TX (Bug 2).
 */
@QuarkusTest
class MultiDatasourceTransactionTest {

	@Inject
	BookStoreRepository bookStoreRepository;

	@Inject
	@DataSource(Constant.DATASOURCE2)
	UserRoleRepository userRoleRepository;

	/**
	 * Bug 2 regression — single datasource, REQUIRES_NEW isolation.
	 *
	 * Outer TX caches conn A in TX_CONNECTION (static ThreadLocal, old code).
	 * REQUIRES_NEW suspends the outer TX — afterCompletion is NOT fired,
	 * so TX_CONNECTION still holds conn A. The inner TX reuses conn A which
	 * is enlisted in the SUSPENDED outer TX → Narayana throws
	 * "Enlisted connection used without active transaction".
	 *
	 * With the TSR-based fix: TSR.getResource is evaluated against the CURRENT
	 * transaction. The inner TX is a fresh transaction with no cached resource,
	 * so it obtains its own connection B. After inner commits, outer resumes and
	 * its resource (conn A) is still correctly bound in the outer TX's TSR slot.
	 */
	@Test
	void requiresNewGetsOwnConnectionNotOuterTx() {
		QuarkusTransaction.requiringNew().run(() -> {
			bookStoreRepository.findAll(); // outer TX: conn A bound to outer TX scope

			// Bug: TX_CONNECTION retains conn A after suspension →
			//      "Enlisted connection used without active transaction"
			// Fix: inner TX has its own TSR scope → fresh conn B acquired
			QuarkusTransaction.requiringNew().run(() -> bookStoreRepository.findAll());

			// Outer TX resumes — conn A still in outer TX's TSR slot, reused correctly.
			bookStoreRepository.findAll();
		});
	}

	/**
	 * Bug 1 + 2 combined — different datasource inside REQUIRES_NEW.
	 *
	 * Outer TX caches default-DS conn in TX_CONNECTION (static ThreadLocal, old code).
	 * REQUIRES_NEW inner TX: userRoleRepository.findAll() checks TX_CONNECTION, finds
	 * the default-DS conn A, and executes the DB2 query through it → PSQLException
	 * "relation public.user_role does not exist" (table absent on default DS).
	 *
	 * With the fix: inner TX has an independent TSR context. DB2 manager's key has
	 * no resource there → fresh DB2 connection obtained → query succeeds.
	 */
	@Test
	void requiresNewDb2QueryNotBleedingFromOuterDefaultDs() {
		QuarkusTransaction.requiringNew().run(() -> {
			bookStoreRepository.findAll(); // default-DS conn cached in outer TX's TSR slot

			// Bug: TX_CONNECTION holds default-DS conn A → DB2 query executed on wrong conn →
			//      PSQLException "relation public.user_role does not exist"
			// Fix: inner TX's TSR has no DB2 resource → own DB2 conn opened → OK
			List<UserRole> roles = QuarkusTransaction.requiringNew()
					.call(() -> userRoleRepository.findAll());

			assertNotNull(roles);
		});
	}

	/**
	 * Smoke test: DB2 datasource is accessible in its own transaction.
	 */
	@Test
	void db2DataSourceWorksInOwnTransaction() {
		List<UserRole> roles = QuarkusTransaction.requiringNew()
				.call(() -> userRoleRepository.findAll());
		assertNotNull(roles);

		List<BookStore> stores = QuarkusTransaction.requiringNew()
				.call(() -> bookStoreRepository.findAll());
		assertFalse(stores.isEmpty());
	}
}
