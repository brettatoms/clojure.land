FROM clojure:tools-deps-trixie-slim AS base

FROM base AS build
RUN apt update && \
    apt install -y nodejs npm && \
    apt-get clean
ADD . /build
WORKDIR /build
RUN clj -T:build uber

FROM eclipse-temurin:24-alpine AS prod
COPY --from=build /build/target/clojure-land.jar /
# Port for local development
EXPOSE 3000:3000
# Port for fly.io
EXPOSE 8080:8080
ENV ZODIAC_ASSETS_BUILD=false
CMD ["java", "-jar", "clojure-land.jar"]
