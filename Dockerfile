# Development image for CourseSweep: Java 21 and Maven with the project's
# libraries already downloaded. docker-compose.yml mounts the source code over
# /app, so edits made on the host are used without rebuilding the image.
FROM maven:3.9.11-eclipse-temurin-21

WORKDIR /app

# Download the libraries and plugins first so this layer is cached until
# pom.xml changes.
COPY pom.xml ./
COPY .mvn/jvm.config .mvn/jvm.config
RUN mvn -B -q dependency:go-offline

COPY src ./src
COPY public ./public
RUN mvn -B -q compile exec:help

# Inside a container the server must listen on all interfaces, not just
# localhost, or the published port cannot reach it.
ENV COURSESWEEP_HOST=0.0.0.0 \
    PORT=8080

EXPOSE 8080

CMD ["mvn", "-B", "-q", "compile", "exec:java"]
