FROM docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY .mvn/settings.xml /root/.m2/settings.xml
COPY src ./src
RUN mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=3 clean package -DskipTests

FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
ENV LOG_DIR=/app/logs
COPY --from=build /build/target/flash-sale-system-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
