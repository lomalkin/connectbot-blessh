# JDK 17 as in the fork CI; the Android SDK lives in the mounted build directory.
FROM eclipse-temurin:17-jdk
RUN apt-get update && apt-get install -y --no-install-recommends git unzip curl && rm -rf /var/lib/apt/lists/*
