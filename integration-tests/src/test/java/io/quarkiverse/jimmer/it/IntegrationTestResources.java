package io.quarkiverse.jimmer.it;

import java.util.HashMap;
import java.util.Map;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class IntegrationTestResources implements QuarkusTestResourceLifecycleManager {

	private static final int REDIS_PORT = 6379;

	@SuppressWarnings("resource")
	private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.2");
	@SuppressWarnings("resource")
	private final PostgreSQLContainer postgres2 = new PostgreSQLContainer("postgres:16.2");
	@SuppressWarnings("resource")
	private final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
			.withExposedPorts(REDIS_PORT);

	@Override
	public Map<String, String> start() {
		postgres.start();
		postgres2.start();
		redis.start();

		String redisUrl = "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT);

		Map<String, String> config = new HashMap<>();
		config.put("quarkus.datasource.jdbc.url", postgres.getJdbcUrl());
		config.put("quarkus.datasource.username", postgres.getUsername());
		config.put("quarkus.datasource.password", postgres.getPassword());
		config.put("quarkus.datasource.DB2.jdbc.url", postgres2.getJdbcUrl());
		config.put("quarkus.datasource.DB2.username", postgres2.getUsername());
		config.put("quarkus.datasource.DB2.password", postgres2.getPassword());
		config.put("quarkus.redis.hosts", redisUrl);
		config.put("quarkus.redisson.single-server-config.address", redisUrl);
		return config;
	}

	@Override
	public void stop() {
		postgres.stop();
		postgres2.stop();
		redis.stop();
	}

	@Override
	public void inject(TestInjector testInjector) {
		testInjector.injectIntoFields(redis, field -> field.getName().equals("redisContainer")
				&& field.getType().equals(GenericContainer.class));
	}
}
