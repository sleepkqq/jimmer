package io.quarkiverse.jimmer.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.babyfish.jimmer.sql.TypedTuple;
import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

class TupleEnumReflectionTest {

    @Test
    void registersEnumFieldsUsedOnlyInTupleProjections() throws IOException {
        Index index = Index.of(Candidate.class, Lane.class);
        List<ReflectiveClassBuildItem> registrations = new ArrayList<>();
        new JimmerProcessor().registerJimmerClassesForReflection(
                new CombinedIndexBuildItem(index, index), registrations::add);

        assertTrue(registrations.stream().anyMatch(item -> item.isFields()
                && item.getClassNames().contains(Lane.class.getName())));
    }

    @TypedTuple
    record Candidate(Lane lane) {
    }

    enum Lane {
        NEAR
    }
}
