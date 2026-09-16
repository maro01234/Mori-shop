FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/mori-shop.jar /app/shop.jar
USER 10001:10001
ENV HOST=0.0.0.0 PORT=8087
EXPOSE 8087
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/shop.jar"]
