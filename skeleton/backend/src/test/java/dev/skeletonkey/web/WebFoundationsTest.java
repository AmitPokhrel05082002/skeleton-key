package dev.skeletonkey.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.config.JacksonConfig;
import dev.skeletonkey.domain.route.RouterException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class WebFoundationsTest {
    @RestController
    static class Probe {
        @GetMapping("/router") String router() {
            throw new RouterException(403, "FIELD_NOT_WRITABLE", "no", List.of(new ApiViolation("a", "readonly")));
        }
        @GetMapping("/lock") String lock() { throw new OptimisticLockingFailureException("x"); }
        @GetMapping("/missing") String missing() { throw new NotFound("nope"); }
        @GetMapping("/history") String history() { return "ok"; }
    }

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void routerExceptionMapsToErrorShapeWithRequestId() throws Exception {
        mvc.perform(get("/router").header("X-Request-Id", "req-1"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Request-Id", "req-1"))
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.code", is("FIELD_NOT_WRITABLE")))
                .andExpect(jsonPath("$.requestId", is("req-1")))
                .andExpect(jsonPath("$.violations", hasSize(1)))
                .andExpect(jsonPath("$.violations[0].field", is("a")));
    }

    @Test
    void requestIdGeneratedWhenAbsent() throws Exception {
        mvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", notNullValue()))
                .andExpect(jsonPath("$.code", is("NOT_FOUND")))
                .andExpect(jsonPath("$.requestId", notNullValue()));
    }

    @Test
    void optimisticLockIsConcurrentModification() throws Exception {
        mvc.perform(get("/lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
    }

    @Test
    void deleteOnReadOnlyRouteIsNotSupported() throws Exception {
        mvc.perform(delete("/history"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code", is("NOT_SUPPORTED")));
    }

    @Test
    void instantSerializedAsUtcMillis() throws Exception {
        ObjectMapper om = new ObjectMapper().registerModule(JacksonConfig.instantModule());
        org.assertj.core.api.Assertions.assertThat(om.writeValueAsString(Instant.parse("2024-03-05T01:02:03.4Z")))
                .isEqualTo("\"2024-03-05T01:02:03.400Z\"");
    }
}