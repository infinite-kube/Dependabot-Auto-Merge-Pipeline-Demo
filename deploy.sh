#!/bin/bash
# deploy.sh - Simple deployment script for the demo app
# This script stops any existing container, rebuilds, and starts fresh

set -e

APP_NAME="dependabot-demo-app"
PORT=8080

echo "========================================="
echo "  Deploying ${APP_NAME}"
echo "========================================="

# Stop and remove existing container if running
if docker ps -a --format '{{.Names}}' | grep -q "^${APP_NAME}$"; then
    echo "Stopping existing container..."
    docker stop ${APP_NAME} || true
    docker rm ${APP_NAME} || true
fi

# Build the new image
echo "Building Docker image..."
docker build -t ${APP_NAME}:latest .

# Run the container
echo "Starting container on port ${PORT}..."
docker run -d \
    --name ${APP_NAME} \
    -p ${PORT}:80 \
    --restart unless-stopped \
    ${APP_NAME}:latest

# Verify it's running
echo "Verifying deployment..."
sleep 2

if docker ps --format '{{.Names}}' | grep -q "^${APP_NAME}$"; then
    echo "========================================="
    echo "  ✅ Deployment Successful!"
    echo "  Access at: http://localhost:${PORT}"
    echo "========================================="
else
    echo "❌ Deployment failed - container not running"
    exit 1
fi