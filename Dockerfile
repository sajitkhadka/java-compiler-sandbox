# Image for the cluster (k8s-config/java-sandbox). Built by .github/workflows/deploy.yml on a vX.Y.Z tag.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/online-java-compiler-*.jar /app.jar

# The runtime needs a full JDK, not a JRE: CompilerService compiles submitted code with
# ToolProvider.getSystemJavaCompiler() and then runs it with the `java` launcher.
FROM eclipse-temurin:21-jdk
# The pod runs as uid/gid 1000 with a read-only root filesystem and only /tmp writable
# (see k8s-config/java-sandbox/java-sandbox-deployment.yaml), so nothing may write elsewhere.
# uid/gid 1000 already exists in the base image (`ubuntu`), so no user needs creating.
COPY --from=build /app.jar /app.jar
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
