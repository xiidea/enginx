package net.xiidea.enginx.api.error;

import net.xiidea.enginx.application.shared.AuditRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A request that never reaches a controller is the caller's mistake, and must be reported as one.
 *
 * <p>Every one of these previously fell through to the catch-all handler and came back {@code 500}
 * with a correlation id — telling the caller to report a bug that was theirs, and logging an error
 * that would page somebody for a typed URL.
 */
class UnmatchedRequestTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new ApiExceptionHandler(mock(AuditRecorder.class)))
            .build();

    /**
     * Raised by the resource handler a request falls through to when nothing has mapped it, which
     * is what an unknown path under {@code /api} does. It is thrown here rather than reached by an
     * unmapped URL because standalone MockMvc has no resource handler to fall through to.
     */
    @Test
    @DisplayName("an unknown path is 404, not 500")
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://enginx.dev/problems/no-such-endpoint"))
                .andExpect(jsonPath("$.title").value("No such endpoint"))
                // A correlation id means "we logged a fault, quote this". Nothing was logged.
                .andExpect(jsonPath("$.reference").doesNotExist());
    }

    @Test
    @DisplayName("the wrong method is 405, and says which methods are allowed")
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mvc.perform(post("/probe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value("https://enginx.dev/problems/method-not-allowed"))
                // Required by RFC 9110, and the only thing that tells a caller what to use instead.
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")));
    }

    @Test
    @DisplayName("a body this endpoint cannot read is 415")
    void unreadableBodyIsUnsupportedMediaType() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("https://enginx.dev/problems/unsupported-media-type"));
    }

    @Test
    @DisplayName("an Accept header nothing matches is 406")
    void unmatchableAcceptIsNotAcceptable() throws Exception {
        mvc.perform(get("/probe").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.type").value("https://enginx.dev/problems/not-acceptable"));
    }

    @Test
    @DisplayName("a request that does match is untouched")
    void matchedRequestStillWorks() throws Exception {
        mvc.perform(get("/probe")).andExpect(status().isOk());
    }

    @RestController
    @RequestMapping("/probe")
    static class Probe {

        @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
        String get() {
            return "\"ok\"";
        }

        @PostMapping(path = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        void body(@org.springframework.web.bind.annotation.RequestBody String payload) {
        }

        @GetMapping("/missing")
        void missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/probe/missing", "/probe/missing");
        }
    }
}
