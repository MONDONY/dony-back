# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml ./
# Download dependencies (cached layer if pom.xml unchanged)
RUN ./mvnw dependency:go-offline -q

COPY src ./src
RUN ./mvnw package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S dony && adduser -S dony -G dony

COPY --from=builder /build/target/dony-back-*.jar app.jar

RUN chown dony:dony app.jar
USER dony

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
