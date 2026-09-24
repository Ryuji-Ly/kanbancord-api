# The jar runs on any CPU, so build it once on the machine running the build instead of under
# emulation for each platform. Only the runtime stage below is per platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY . .

RUN chmod +x ./mvnw \
    && ./mvnw clean package -DskipTests


FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]