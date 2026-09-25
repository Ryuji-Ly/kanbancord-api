package com.kanbancord_api.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigInteger;

/**
 * JSON sent by the API, over HTTP and WebSocket alike (Spring Boot uses this mapper for both).
 *
 * <p>JavaScript numbers hold integers exactly only up to 2^53. Discord ids are larger, so a number
 * beyond that would be silently rounded by the web app and the bot. Such numbers are written as
 * strings instead, wherever they appear: response fields, audit log changes, event payloads. Small
 * numbers (task ids, positions, counts) stay numbers.
 */
@Configuration
public class JsonConfig {

    /** The largest integer a JavaScript number holds exactly. */
    static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    @Bean
    public Module largeIntegersAsStrings() {
        SimpleModule module = new SimpleModule("LargeIntegersAsStrings");
        JsonSerializer<Long> longs = new JsonSerializer<>() {
            @Override
            public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value > MAX_SAFE_INTEGER || value < -MAX_SAFE_INTEGER) {
                    gen.writeString(value.toString());
                } else {
                    gen.writeNumber(value);
                }
            }
        };
        module.addSerializer(Long.class, longs);
        module.addSerializer(Long.TYPE, longs);
        module.addSerializer(BigInteger.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigInteger value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value.bitLength() > 53) {
                    gen.writeString(value.toString());
                } else {
                    gen.writeNumber(value);
                }
            }
        });
        return module;
    }
}
