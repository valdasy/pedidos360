# ---------- Etapa 1: compilar con Maven (JDK completo) ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
# Primero solo el pom: si no cambian las dependencias, Docker reutiliza esta capa
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package && cp target/*.jar app.jar

# ---------- Etapa 2: imagen final liviana (solo JRE + jar) ----------
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 spring
COPY --from=build /app/app.jar app.jar
USER spring
# Usa hasta el 75 % de la memoria asignada al contenedor
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
