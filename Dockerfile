# Stage 1: Build the application using Maven
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy pom.xml and download dependencies (for caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and web directory
COPY src ./src
COPY web ./web

# Build the application
RUN mvn clean package -DskipTests

# Stage 2: Create the production image
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the fat jar from the builder stage
COPY --from=builder /app/target/lifeflow-backend-1.0.0.jar app.jar

# Copy the web directory (needed for serving static HTML files)
COPY --from=builder /app/web ./web

# Expose the default port
EXPOSE 8080

# Command to run the application in web mode
CMD ["java", "-jar", "app.jar", "web"]
