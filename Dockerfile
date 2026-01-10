# Build stage - using Java 25
FROM clojure:temurin-25-tools-deps-trixie-slim AS build

# Install Node.js for asset building
RUN apt-get update && \
    apt-get install -y --no-install-recommends nodejs npm && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy deps.edn first for Clojure dependency caching
COPY deps.edn ./

# Download Clojure dependencies (cached unless deps.edn changes)
RUN clojure -P

# Copy source code and build (npm ci runs via build.clj)
COPY . .
RUN clj -T:build uber

# Production stage - Java 25 runtime
FROM eclipse-temurin:25-jre-alpine AS prod

COPY --from=build /build/target/clojure-land.jar /

EXPOSE 3000
EXPOSE 8080

ENV ZODIAC_ASSETS_BUILD=false

CMD ["java", "-jar", "clojure-land.jar"]
