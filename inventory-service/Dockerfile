FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY gradlew build.gradle ./
COPY gradle ./gradle
COPY common ./common
COPY inventory-service ./inventory-service
# The root settings.gradle includes all 8 modules, but this build context only has
# common/ and inventory-service/ — regenerate a settings.gradle scoped to just what's actually
# present, otherwise Gradle fails trying to configure sibling modules with no directory.
RUN cat > settings.gradle <<'GRADLE_EOF' && \
    chmod +x gradlew && ./gradlew :inventory-service:bootJar -x test --no-daemon
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}
rootProject.name = 'ecommerce-platform'
include 'common'
include 'inventory-service'
GRADLE_EOF

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /app/inventory-service/build/libs/*.jar app.jar
USER spring:spring
EXPOSE 8083
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "--sun-misc-unsafe-memory-access=allow", "-jar", "app.jar"]
