# deploy.ps1 - Deployment script for Windows
# Run with: powershell -ExecutionPolicy Bypass -File deploy.ps1

$ErrorActionPreference = "Stop"

$APP_NAME = "dependabot-demo-app"
$PORT = 8080

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Deploying $APP_NAME" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# Stop and remove existing container if running
Write-Host "Checking for existing container..." -ForegroundColor Yellow
$existingContainer = docker ps -a --format '{{.Names}}' | Where-Object { $_ -eq $APP_NAME }

if ($existingContainer) {
    Write-Host "Stopping existing container..." -ForegroundColor Yellow
    docker stop $APP_NAME 2>$null
    docker rm $APP_NAME 2>$null
}

# Build and run using docker-compose
Write-Host "Building and starting container..." -ForegroundColor Yellow
docker-compose up -d --build

# Verify it's running
Write-Host "Verifying deployment..." -ForegroundColor Yellow
Start-Sleep -Seconds 3

$runningContainer = docker ps --format '{{.Names}}' | Where-Object { $_ -eq $APP_NAME }

if ($runningContainer) {
    Write-Host "=========================================" -ForegroundColor Green
    Write-Host "  Deployment Successful!" -ForegroundColor Green
    Write-Host "  Access at: http://localhost:$PORT" -ForegroundColor Green
    Write-Host "=========================================" -ForegroundColor Green
} else {
    Write-Host "Deployment failed - container not running" -ForegroundColor Red
    exit 1
}