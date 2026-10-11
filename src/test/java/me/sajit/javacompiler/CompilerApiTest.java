package me.sajit.javacompiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CompilerApiTest {

    private static final String HELLO =
            "public class Main { public static void main(String[] a) { System.out.println(\"Hello, World!\"); } }";

    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    /** Each call uses its own client IP so tests never share a rate-limit bucket. */
    private ResultActions compile(Map<String, String> body, String ip) throws Exception {
        return mvc.perform(post("/api/compile")
                .header("CF-Connecting-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private ResultActions compile(Map<String, String> body) throws Exception {
        return compile(body, UUID.randomUUID().toString());
    }

    @Test
    void runsHelloWorld() throws Exception {
        compile(Map.of("code", HELLO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Hello, World!"));
    }

    @Test
    void reportsCompileErrorWithLineNumber() throws Exception {
        String code = "public class Main {\n public static void main(String[] a) {\n int x = ;\n }\n}";
        compile(Map.of("code", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[0].line").value(3));
    }

    @Test
    void feedsInputToStdin() throws Exception {
        String code = "import java.util.Scanner; public class Main { public static void main(String[] a) {"
                + " Scanner s = new Scanner(System.in); System.out.println(s.nextInt() + s.nextInt()); } }";
        compile(Map.of("code", code, "input", "2 3"))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("5"));
    }

    @Test
    void reportsRuntimeExceptionAsError() throws Exception {
        String code = "public class Main { public static void main(String[] a) { throw new IllegalStateException(\"boom\"); } }";
        compile(Map.of("code", code))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("boom")));
    }

    @Test
    void timesOutInfiniteLoop() throws Exception {
        String code = "public class Main { public static void main(String[] a) { while (true) {} } }";
        compile(Map.of("code", code))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[0].message").value("Execution timed out"));
    }

    @Test
    void largeOutputDoesNotHangAndIsTruncated() throws Exception {
        String code = "public class Main { public static void main(String[] a) {"
                + " for (int i = 0; i < 200000; i++) System.out.println(\"line \" + i); } }";
        compile(Map.of("code", code))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("output truncated")));
    }

    @Test
    void picksPublicClassEvenWhenHelperComesFirst() throws Exception {
        String code = "class Helper { static int one() { return 1; } }\n"
                + "public class Main { public static void main(String[] a) { System.out.println(Helper.one()); } }";
        compile(Map.of("code", code))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("1"));
    }

    @Test
    void childProcessDoesNotInheritServerEnvironment() throws Exception {
        String code = "public class Main { public static void main(String[] a) {"
                + " System.out.println(System.getenv().keySet().stream().noneMatch(k -> k.equalsIgnoreCase(\"JAVA_HOME\")"
                + " || k.equalsIgnoreCase(\"USERNAME\") || k.equalsIgnoreCase(\"HOME\") || k.equalsIgnoreCase(\"USERPROFILE\"))); } }";
        compile(Map.of("code", code))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("true"));
    }

    @Test
    void rejectsMissingCode() throws Exception {
        compile(Map.of("input", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsCodeWithoutClass() throws Exception {
        compile(Map.of("code", "int x = 1;")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedCode() throws Exception {
        String code = HELLO + " ".repeat(CompilerService.MAX_CODE_CHARS);
        compile(Map.of("code", code)).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void rateLimitsPerClientAndSendsHeaders() throws Exception {
        String ip = UUID.randomUUID().toString();
        // A bad request is still counted, and is cheap to serve.
        for (int i = 0; i < 30; i++) {
            compile(Map.of("input", "x"), ip).andExpect(status().isBadRequest());
        }
        compile(Map.of("input", "x"), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
        // A different client is unaffected.
        compile(Map.of("input", "x")).andExpect(status().isBadRequest())
                .andExpect(header().exists("X-RateLimit-Remaining"));
    }

    @Test
    void clientIpPrefersCloudflareHeaderThenFirstForwardedEntry() {
        assertEquals("1.1.1.1", CompilerController.clientIp("1.1.1.1", "9.9.9.9"));
        assertEquals("2.2.2.2", CompilerController.clientIp(null, "2.2.2.2, 10.0.0.1"));
        assertEquals("UNKNOWN", CompilerController.clientIp(" ", null));
    }

    @Test
    void extractsClassNames() {
        assertEquals("Main", CompilerService.extractClassName("class A {} public class Main {}"));
        assertEquals("Real", CompilerService.extractClassName("// class Fake\n/* class Fake2 */ class Real {}"));
        assertNull(CompilerService.extractClassName("int x;"));
    }
}
