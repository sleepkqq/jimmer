package io.quarkiverse.jimmer.runtime.generator;

import java.util.UUID;

import org.babyfish.jimmer.sql.meta.UserIdGenerator;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

public class UUIDv7IdGenerator implements UserIdGenerator<UUID> {

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    @Override
    public UUID generate(Class<?> entityType) {
        return GENERATOR.generate();
    }
}
