# Multi-stage Dockerfile for the demo application
# This demonstrates dependencies that Dependabot will monitor

# Stage 1: Node.js application
FROM node:25-alpine AS node-app

WORKDIR /app

# Copy package files
COPY package*.json ./

# Install dependencies
RUN npm ci --only=production

# Copy application code
COPY src/index.js ./src/

# Expose port
EXPOSE 3000

# Stage 2: Python application
FROM python:3.11-slim AS python-app

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    gcc \
    postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements
COPY requirements.txt .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY src/app.py ./src/

# Expose port
EXPOSE 5000

# Stage 3: Java application (if using Maven)
FROM maven:3.9-openjdk-17 AS java-build

WORKDIR /app

# Copy pom.xml
COPY pom.xml .

# Download dependencies
RUN mvn dependency:go-offline

# Copy source code (if available)
# COPY src ./src

# Build application
# RUN mvn clean package

# Stage 4: Final runtime image (Node.js as primary)
FROM node:25-alpine

WORKDIR /app

# Install additional tools
RUN apk add --no-cache \
    curl \
    bash \
    git \
    openssh

# Copy from Node.js stage
COPY --from=node-app /app /app

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:3000/health || exit 1

# Create non-root user
RUN addgroup -g 1001 -S appuser && \
    adduser -u 1001 -S appuser -G appuser && \
    chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Default command
CMD ["node", "src/index.js"]

# Labels for metadata
LABEL maintainer="InfiniteKube Team"
LABEL version="1.0.0"
LABEL description="Dependabot Auto-merge Pipeline Demo Application"
