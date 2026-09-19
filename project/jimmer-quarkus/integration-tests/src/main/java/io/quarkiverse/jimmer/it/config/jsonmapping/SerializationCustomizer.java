package io.quarkiverse.jimmer.it.config.jsonmapping;

import jakarta.enterprise.context.ApplicationScoped;

import org.babyfish.jimmer.jackson.codec.JsonCodec;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.runtime.Customizer;


@ApplicationScoped
public class SerializationCustomizer implements Customizer {

    @Override
    public void customize(JSqlClient.Builder builder) {
        builder.setSerializedTypeJsonCodec(AuthUser.class, JsonCodec.jsonCodec());
    }
}
