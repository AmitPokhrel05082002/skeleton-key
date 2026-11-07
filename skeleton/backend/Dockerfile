# Build context: skeleton/backend. The shared schema is supplied as the named
# build context "schema" (see docker-compose.yml additional_contexts).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build/skeleton/backend
COPY --from=schema . /build/schema
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/skeleton/backend/target/skeleton-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
