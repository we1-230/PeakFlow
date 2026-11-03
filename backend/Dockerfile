FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
RUN mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>ali</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
# 堆大小交给 JAVA_OPTS 控制：本地默认 512m，生产覆盖文件里按机器内存调小
ENV JAVA_OPTS="-Xmx512m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
