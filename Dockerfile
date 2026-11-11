# One image: React terminal + gateway + engine.   docker build -t tradevision .
# Stage 1: build the terminal. Vite writes straight into the gateway's static resources.
FROM node:22.11.0-alpine AS ui
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# Stage 2: build the jar (dependencies first, so source edits do not re-download Maven Central).
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY engine/pom.xml engine/pom.xml
COPY bench/pom.xml bench/pom.xml
COPY gateway/pom.xml gateway/pom.xml
RUN mvn -q -B -pl gateway -am dependency:go-offline -DskipTests || true
COPY engine engine
COPY bench bench
COPY gateway gateway
COPY --from=ui /src/gateway/src/main/resources/static gateway/src/main/resources/static
RUN mvn -q -B -pl gateway -am package -DskipTests

# Stage 3: runtime. JRE only, non-root.
FROM eclipse-temurin:21.0.5_11-jre
RUN useradd --system --uid 10001 tradevision && mkdir /data && chown tradevision /data
USER tradevision
WORKDIR /app
COPY --from=build /src/gateway/target/tradevision-gateway-*.jar app.jar
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms512m", "-Xmx1g", "-jar", "/app/app.jar", "--tradevision.data-dir=/data"]
