# One image: React terminal + gateway + engine. Build: docker build -t tradevision .
FROM node:24-alpine AS ui
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# vite.config.ts writes to ../gateway/src/main/resources/static
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY engine engine
COPY bench bench
COPY gateway gateway
COPY --from=ui /src/gateway/src/main/resources/static gateway/src/main/resources/static
RUN mvn -q -B -pl gateway -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/gateway/target/tradevision-gateway-*.jar app.jar
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseZGC", "-Xms512m", "-Xmx1g", "-jar", "/app/app.jar", "--tradevision.data-dir=/data"]
