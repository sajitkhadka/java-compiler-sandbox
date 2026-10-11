package me.sajit.javacompiler;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class CompilerService {
    static final int MAX_CODE_CHARS = 64 * 1024;
    static final int MAX_OUTPUT_CHARS = 64 * 1024;
    private static final int MAX_INPUT_COUNT = 100;
    private static final int MAX_CONCURRENT_RUNS = 4;
    private static final int TIMEOUT = 5; //sec
    private static final int READER_JOIN_TIMEOUT = 2; //sec
    // Only what the JVM needs to start; everything else in the server's environment is withheld from user code.
    private static final Set<String> SAFE_ENV = Set.of("PATH", "SYSTEMROOT", "TEMP", "TMP", "TMPDIR");

    // One cheap virtual thread per stdin writer / output reader; the semaphore bounds how many exist.
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore runSlots = new Semaphore(MAX_CONCURRENT_RUNS);

    private static final Pattern COMMENTS = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern PUBLIC_CLASS =
            Pattern.compile("\\bpublic\\s+(?:(?:final|abstract|strictfp)\\s+)*class\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern ANY_CLASS = Pattern.compile("\\bclass\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    /** The public class if there is one (javac requires the file to carry its name), else the first class. */
    static String extractClassName(String sourceCode) {
        String code = COMMENTS.matcher(sourceCode).replaceAll(" ");
        Matcher matcher = PUBLIC_CLASS.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = ANY_CLASS.matcher(code);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Map<String, Object> validateInputArguments(String javaCode, String inputStr) {
        Map<String, Object> result = new HashMap<>();
        result.put("isValid", true);
        int expectedInputCount = countExpectedInputs(javaCode);
        List<Map<String, Object>> errorDetails = new ArrayList<>();

        // If no inputs are expected, return valid
        if (expectedInputCount == 0) {
            return result;
        }

        // If input is empty and inputs are expected
        if (inputStr == null || inputStr.trim().isEmpty()) {
            result.put("isValid", false);
            Map<String, Object> errorInfo = new HashMap<>();
            errorInfo.put("message", "Input required: Expected " + expectedInputCount + " input argument(s).");
            errorInfo.put("line", findInputLineNumber(javaCode));
            errorDetails.add(errorInfo);
            result.put("errors", errorDetails);
            return result;
        }

        // Check input count
        String[] inputArgs = inputStr.trim().split("\\s+");

        if (inputArgs.length < expectedInputCount) {
            result.put("isValid", false);
            Map<String, Object> errorInfo = new HashMap<>();
            errorInfo.put("message", String.format(
                    "Input mismatch: Expected %d input argument(s), but got %d.",
                    expectedInputCount,
                    inputArgs.length));
            errorInfo.put("line", findInputLineNumber(javaCode));
            errorDetails.add(errorInfo);
            result.put("errors", errorDetails);
        }

        return result;
    }

    private int countExpectedInputs(String javaCode) {
        Pattern scannerPattern = Pattern.compile("new\\s+Scanner\\s*\\(\\s*System\\.in\\s*\\)");
        Pattern nextIntPattern = Pattern.compile("\\.nextInt\\(\\)");
        Pattern nextDoublePattern = Pattern.compile("\\.nextDouble\\(\\)");
        Pattern nextLinePattern = Pattern.compile("\\.nextLine\\(\\)");

        Matcher scannerMatcher = scannerPattern.matcher(javaCode);

        if (!scannerMatcher.find()) {
            return 0;
        }

        int inputCount = 0;
        Matcher nextIntMatcher = nextIntPattern.matcher(javaCode);
        Matcher nextDoubleMatcher = nextDoublePattern.matcher(javaCode);
        Matcher nextLineMatcher = nextLinePattern.matcher(javaCode);

        while (nextIntMatcher.find()) inputCount++;
        while (nextDoubleMatcher.find()) inputCount++;
        while (nextLineMatcher.find()) inputCount++;

        return inputCount;
    }

    private int findInputLineNumber(String javaCode) {
        String[] lines = javaCode.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("new Scanner(System.in)") ||
                    lines[i].contains(".nextInt()") ||
                    lines[i].contains(".nextDouble()") ||
                    lines[i].contains(".nextLine()")) {
                return i + 1;
            }
        }
        return -1;
    }

    private ResponseEntity<Map<String, Object>> formatMessage(HttpStatus httpStatus, String status, String message) {
        List<Map<String, Object>> errorDetails = new ArrayList<>();
        Map<String, Object> errorInfo = new HashMap<>();
        errorInfo.put("line", 0);
        errorInfo.put("message", message == null ? "Unexpected error" : message);
        errorDetails.add(errorInfo);
        return ResponseEntity.status(httpStatus).body(Map.of(
                "status", status,
                "errors", errorDetails
        ));
    }

    private ResponseEntity<Map<String, Object>> formatMessage(String status, String message) {
        return formatMessage(HttpStatus.OK, status, message);
    }

    /** Drains the stream completely but keeps only the first {@code MAX_OUTPUT_CHARS} chars, so a chatty program can neither block on a full pipe nor exhaust our heap. */
    private static String readCapped(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                int room = MAX_OUTPUT_CHARS - sb.length();
                if (room > 0) {
                    sb.append(buf, 0, Math.min(n, room));
                }
                if (n > room) {
                    truncated = true;
                }
            }
        }
        if (truncated) {
            sb.append("\n... output truncated at ").append(MAX_OUTPUT_CHARS).append(" characters");
        }
        return sb.toString();
    }

    private ResponseEntity<Map<String, Object>> executeCodeWithInput(Path executionDir, String className, String javaCode, String input) {
        int expectedInputCount = countExpectedInputs(javaCode);
        String[] inputs = input == null || input.isBlank() ? new String[0] : input.trim().split("\\s+");

        if (inputs.length < expectedInputCount) {
            return formatMessage("error", String.format(
                    "Insufficient input: Expected %d input argument(s), but got %d.",
                    expectedInputCount, inputs.length
            ));
        } else if (inputs.length > MAX_INPUT_COUNT) {
            return formatMessage("error", String.format(
                    "Too many input: Expected less than %d input argument(s), but got %d.", MAX_INPUT_COUNT, inputs.length));
        }

        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("java", "-Xmx128m", "-Xss512k", "-XX:+ExitOnOutOfMemoryError",
                    "-cp", executionDir.toString(), className);
            // The submitted program must not see the server's environment (tokens, credentials, config).
            Map<String, String> env = processBuilder.environment();
            env.keySet().removeIf(name -> !SAFE_ENV.contains(name.toUpperCase(Locale.ROOT)));
            process = processBuilder.start();
            final Process proc = process;

            // Start the readers first: if they only start after the process exits, anything beyond the pipe buffer deadlocks it.
            Future<String> outputFuture = EXECUTOR.submit(() -> readCapped(proc.getInputStream()));
            Future<String> errorFuture = EXECUTOR.submit(() -> readCapped(proc.getErrorStream()));
            EXECUTOR.submit(() -> {
                try (PrintWriter processInput = new PrintWriter(proc.getOutputStream(), true)) {
                    for (String arg : inputs) {
                        processInput.println(arg);
                    }
                }
            });

            if (!process.waitFor(TIMEOUT, TimeUnit.SECONDS)) {
                return formatMessage("error", "Execution timed out");
            }

            String output = outputFuture.get(READER_JOIN_TIMEOUT, TimeUnit.SECONDS);
            String error = errorFuture.get(READER_JOIN_TIMEOUT, TimeUnit.SECONDS);
            String combinedOutput = (output + error).trim();

            if (process.exitValue() != 0) {
                return formatMessage("error", combinedOutput.isEmpty() ? "Execution failed" : combinedOutput);
            }
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", combinedOutput.isEmpty() ? "No output" : combinedOutput
            ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return formatMessage("error", "Execution interrupted");
        } catch (Exception e) {
            return formatMessage("error", e.getMessage());
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    public ResponseEntity<Map<String, Object>> compile(String javaCode, String inputStr) {
        if (javaCode == null || javaCode.isBlank()) {
            return formatMessage(HttpStatus.BAD_REQUEST, "error", "The 'code' field is required.");
        }
        if (javaCode.length() > MAX_CODE_CHARS) {
            return formatMessage(HttpStatus.PAYLOAD_TOO_LARGE, "error",
                    "Code is too large: the limit is " + MAX_CODE_CHARS + " characters.");
        }
        String className = extractClassName(javaCode);
        if (className == null) {
            return formatMessage(HttpStatus.BAD_REQUEST, "error", "No class declaration found in the code.");
        }

        Map<String, Object> validationResult = validateInputArguments(javaCode, inputStr);
        if (!(boolean) validationResult.get("isValid")) {
            return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "errors", validationResult.get("errors")
            ));
        }

        if (!runSlots.tryAcquire()) {
            ResponseEntity<Map<String, Object>> busy = formatMessage(HttpStatus.SERVICE_UNAVAILABLE, "error",
                    "The server is busy running other programs. Please retry in a few seconds.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "5")
                    .body(busy.getBody());
        }

        Path requestTempDir = null;
        try {
            requestTempDir = Files.createTempDirectory("java-compiler-request-");
            Path sourceFile = requestTempDir.resolve(className + ".java");
            Files.writeString(sourceFile, javaCode);

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return formatMessage("error", "No Java compiler available: run the server on a JDK, not a JRE.");
            }
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            boolean compilationSuccess;
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
                Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjects(sourceFile.toFile());
                compilationSuccess = compiler.getTask(null, fileManager, diagnostics,
                        List.of("-d", requestTempDir.toString()), null, compilationUnits).call();
            }

            if (!compilationSuccess) {
                List<Map<String, Object>> errorDetails = new ArrayList<>();
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    Map<String, Object> errorInfo = new HashMap<>();
                    long lineNumber = diagnostic.getLineNumber();
                    if (lineNumber > 0) {
                        errorInfo.put("line", (int) lineNumber);
                    }
                    errorInfo.put("message", diagnostic.getMessage(null));
                    errorDetails.add(errorInfo);
                }
                return ResponseEntity.ok(Map.of(
                        "status", "error",
                        "errors", errorDetails
                ));
            }
            return executeCodeWithInput(requestTempDir, className, javaCode, inputStr);
        } catch (Exception e) {
            return formatMessage("error", e.getMessage());
        } finally {
            runSlots.release();
            deleteDirectory(requestTempDir);
        }
    }

    private void deleteDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ignored) {
            // Best effort: the OS temp cleaner gets whatever is left.
        }
    }
}
