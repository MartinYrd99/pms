# Build stage: compiles the jar with the project's own Maven wrapper, so the image always builds
# with the exact Maven version the repo pins, with no separate Maven base image to keep in sync.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -Dmaven.test.skip=true package

# Runtime stage: JRE only, no build tooling shipped in the final image.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/pms-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
