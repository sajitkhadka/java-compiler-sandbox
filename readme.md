# Java Compiler Sandbox - Free Online Java Compiler & REST API

Compile and run Java code from your browser or from any language with a single HTTP request.
Free, no sign-up, no API key. Built with Spring Boot 3 and Java 21.

🌐 **Live app**: [java-sandbox.sajitkhadka.com](https://java-sandbox.sajitkhadka.com)
📖 **API docs**: [java-sandbox.sajitkhadka.com/docs](https://java-sandbox.sajitkhadka.com/docs)

## 🚀 Features

-   Online Java editor with syntax highlighting (CodeMirror)
-   Free REST API: `POST /api/compile` compiles and runs a snippet
-   Standard-input support (whitespace-separated values fed to `Scanner(System.in)`)
-   Line-accurate compiler errors
-   Per-IP rate limiting with Bucket4j
-   Container-ready with Jib

## 🆓 Using the Free API

No authentication or API key is required.

**Base URL**: `https://java-sandbox.sajitkhadka.com`
**Endpoint**: `POST /api/compile`
**Content-Type**: `application/json`

### Request

| Field   | Type   | Required | Description                                                                 |
| ------- | ------ | -------- | --------------------------------------------------------------------------- |
| `code`  | string | yes      | Full Java source containing a class with `public static void main`.        |
| `input` | string | no       | Whitespace-separated values sent to stdin, one per line (max 100 values).  |

### Response

Successful run:

```json
{
    "status": "success",
    "message": "Hello, World!"
}
```

Compilation or runtime failure:

```json
{
    "status": "error",
    "errors": [{ "line": 3, "message": "';' expected" }]
}
```

Compile errors, runtime errors and timeouts are returned with HTTP `200` and `"status": "error"`; check the `status` field.
Malformed requests use real HTTP codes with the same body shape:

| HTTP | When                                                          |
| ---- | ------------------------------------------------------------- |
| 400  | `code` is missing/blank, or contains no class declaration     |
| 413  | `code` is longer than 64 KB (65,536 characters)               |
| 429  | rate limit exceeded (see below)                               |
| 503  | the server is already running its maximum parallel programs; retry after the `Retry-After` seconds |

### Examples

**curl**

```bash
curl -X POST https://java-sandbox.sajitkhadka.com/api/compile \
  -H "Content-Type: application/json" \
  -d '{"code": "public class Main { public static void main(String[] args) { System.out.println(\"Hello, World!\"); } }"}'
```

**With input**

```bash
curl -X POST https://java-sandbox.sajitkhadka.com/api/compile \
  -H "Content-Type: application/json" \
  -d '{"code": "import java.util.Scanner; public class Main { public static void main(String[] a) { Scanner s = new Scanner(System.in); System.out.println(s.nextInt() + s.nextInt()); } }", "input": "2 3"}'
```

**JavaScript**

```js
const res = await fetch("https://java-sandbox.sajitkhadka.com/api/compile", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code: "public class Main { public static void main(String[] a){ System.out.println(1+1); } }" }),
});
console.log(await res.json());
```

**Python**

```python
import requests

r = requests.post(
    "https://java-sandbox.sajitkhadka.com/api/compile",
    json={"code": "public class Main { public static void main(String[] a){ System.out.println(1+1); } }"},
)
print(r.json())
```

### ⏱️ Rate limits and quotas

| Limit               | Value                                         |
| ------------------- | --------------------------------------------- |
| Request rate        | **30 requests per 30 seconds per client IP**  |
| Execution timeout   | 5 seconds per run                             |
| Input values        | at most 100 per request                       |
| Code size           | 64 KB                                         |
| Output size         | first 64 KB of output is returned, the rest is truncated |
| Program memory      | 128 MB heap                                   |
| Parallel runs       | 4 programs at once, server-wide               |
| Cost                | Free                                          |

When you exceed the request rate the API returns `HTTP 429 Too Many Requests`:

```json
{ "error": "Too many requests. Please try again later." }
```

Every response carries `X-RateLimit-Limit` and `X-RateLimit-Remaining`; a `429` also carries `Retry-After` (seconds).
Tokens refill continuously, so wait that long and retry. For batch workloads, space requests out
(about one per second sustained) or run your own copy using the instructions below.
The service is provided as-is with no uptime guarantee; don't rely on it for production traffic.

> Note: CORS is restricted to `sajitkhadka.com` origins and `localhost:3000`. Calls from other websites' browser
> code will be blocked; call the API from a server, script or CLI instead.

## 🛠️ Building and Running Locally

Requires JDK 21 (a full JDK, not a JRE, since the app uses the system Java compiler).

```bash
git clone https://github.com/sajitkhadka/java-compiler-sandbox.git
cd java-compiler-sandbox
./mvnw spring-boot:run
```

The app is then available at `http://localhost:8080` (UI at `/`, docs at `/docs`).

Build a jar with `./mvnw clean package`.

## 🐳 Docker

```bash
# Build the image with Jib
./mvnw compile jib:dockerBuild

# Run it
docker run -p 8080:8080 online-java-compiler:latest
```

## 🤝 Contributions

Contributions, issues and feature requests are welcome - open a pull request or an
[issue](https://github.com/sajitkhadka/java-compiler-sandbox/issues).

## 📄 License

[MIT](LICENSE)
