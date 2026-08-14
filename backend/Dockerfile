# eNSP NMS 后端镜像（学校交付）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=delivery \
    JAVA_OPTS="-Xms256m -Xmx768m"
COPY --from=build /build/target/ensp-nms-*.jar /app/app.jar
EXPOSE 8080 1162/udp
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
