FROM gradle:8.5-jdk21 AS build
WORKDIR /workspace/mbotama-pay-backend-1.0
COPY . .
RUN chmod +x gradlew && ./gradlew clean test bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/mbotama-pay-backend-1.0/build/libs/mbotamapay-backend.jar /app/app.jar
ENV PORT=8080
EXPOSE 8080
CMD ["java","-XX:ActiveProcessorCount=2","-jar","/app/app.jar"]
