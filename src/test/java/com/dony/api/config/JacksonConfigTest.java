package com.dony.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    @Test
    void localDateTime_isSerializedAsIsoWithUtcOffset() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder()
                .modules(new JavaTimeModule())
                .featuresToDisable(
                        com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Jackson2ObjectMapperBuilderCustomizer customizer =
                new JacksonConfig().utcLocalDateTimeCustomizer();
        customizer.customize(builder);
        ObjectMapper mapper = builder.build();

        LocalDateTime t = LocalDateTime.of(2026, 5, 3, 12, 0, 0);
        String json = mapper.writeValueAsString(t);

        // Must end with the UTC offset (Z or +00:00) so that Flutter parses it as UTC
        // instead of as a naive local-zone instant.
        assertThat(json).contains("2026-05-03T12:00:00");
        assertThat(json).matches(".*(Z|\\+00:00)\".*");
    }
}
