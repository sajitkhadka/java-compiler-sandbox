package me.sajit.javacompiler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/compile")
public class CompilerController {

    private final CompilerService compilerService;

    // Idle clients expire, so the map cannot grow without bound; the size cap is a backstop against floods of spoofed IPs.
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    private static final int REQUEST_LIMIT = 30;
    private static final Duration TIME_FRAME = Duration.ofSeconds(30);

    public CompilerController(CompilerService compilerService) {
        this.compilerService = compilerService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> compileCode(@RequestBody Map<String, String> request,
                                                           @RequestHeader(value = "CF-Connecting-IP", required = false) String cfConnectingIp,
                                                           @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        String ipAddress = clientIp(cfConnectingIp, forwardedFor);

        ConsumptionProbe probe = buckets.get(ipAddress, this::createNewBucket).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                    .header("X-RateLimit-Limit", String.valueOf(REQUEST_LIMIT))
                    .header("X-RateLimit-Remaining", "0")
                    .body(Map.of("error", "Too many requests. Please try again later."));
        }

        ResponseEntity<Map<String, Object>> result = compilerService.compile(request.get("code"), request.get("input"));
        return ResponseEntity.status(result.getStatusCode())
                .headers(result.getHeaders())
                .header("X-RateLimit-Limit", String.valueOf(REQUEST_LIMIT))
                .header("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()))
                .body(result.getBody());
    }

    // Behind a Cloudflare Tunnel the real client is in CF-Connecting-IP (set by Cloudflare's edge).
    // X-Forwarded-For is the fallback for local runs; only its first entry is the client.
    static String clientIp(String cfConnectingIp, String forwardedFor) {
        if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
            return cfConnectingIp.trim();
        }
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return "UNKNOWN";
    }

    private Bucket createNewBucket(String ipAddress) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(REQUEST_LIMIT, Refill.greedy(REQUEST_LIMIT, TIME_FRAME)))
                .build();
    }
}
