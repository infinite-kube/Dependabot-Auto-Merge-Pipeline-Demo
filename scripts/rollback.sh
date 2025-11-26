#!/bin/bash

# Automated Rollback Script
# Handles rollback procedures for failed deployments

set -e

# Configuration
PR_NUMBER=""
ENVIRONMENT="production"
DEPLOYMENT_TYPE="kubernetes"  # kubernetes, docker, helm
FORCE_ROLLBACK=false
HEALTH_CHECK_URL=""
GITHUB_TOKEN="${GITHUB_TOKEN}"
SLACK_WEBHOOK="${SLACK_WEBHOOK}"

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --pr)
            PR_NUMBER="$2"
            shift 2
            ;;
        --env|--environment)
            ENVIRONMENT="$2"
            shift 2
            ;;
        --type)
            DEPLOYMENT_TYPE="$2"
            shift 2
            ;;
        --force)
            FORCE_ROLLBACK=true
            shift
            ;;
        --health-check-url)
            HEALTH_CHECK_URL="$2"
            shift 2
            ;;
        --help)
            cat <<EOF
Usage: $0 --pr PR_NUMBER [OPTIONS]

Automated rollback script for failed deployments.

Options:
    --pr PR_NUMBER           Pull request number that triggered deployment
    --env ENVIRONMENT        Environment to rollback (default: production)
    --type TYPE             Deployment type (kubernetes|docker|helm) [default: kubernetes]
    --force                 Force rollback without health checks
    --health-check-url URL  URL to check application health
    --help                  Show this help message

Examples:
    # Rollback Kubernetes deployment
    $0 --pr 123 --env production --type kubernetes

    # Force rollback without checks
    $0 --pr 123 --force

    # Rollback with health check verification
    $0 --pr 123 --health-check-url https://app.com/health
EOF
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Validate required parameters
if [[ -z "$PR_NUMBER" ]]; then
    echo -e "${RED}Error: PR number is required${NC}"
    exit 1
fi

# Logging function
log() {
    local level=$1
    shift
    local message="$@"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    
    case $level in
        ERROR)
            echo -e "${RED}[ERROR] ${timestamp}: ${message}${NC}"
            ;;
        WARNING)
            echo -e "${YELLOW}[WARNING] ${timestamp}: ${message}${NC}"
            ;;
        INFO)
            echo -e "${BLUE}[INFO] ${timestamp}: ${message}${NC}"
            ;;
        SUCCESS)
            echo -e "${GREEN}[SUCCESS] ${timestamp}: ${message}${NC}"
            ;;
    esac
    
    # Also log to file
    echo "[${level}] ${timestamp}: ${message}" >> rollback.log
}

# Send notification
send_notification() {
    local status=$1
    local message=$2
    
    if [[ -n "$SLACK_WEBHOOK" ]]; then
        local color="danger"
        if [[ "$status" == "success" ]]; then
            color="good"
        elif [[ "$status" == "warning" ]]; then
            color="warning"
        fi
        
        curl -s -X POST "$SLACK_WEBHOOK" \
            -H 'Content-Type: application/json' \
            -d "{
                \"text\": \"Rollback Alert\",
                \"attachments\": [{
                    \"color\": \"${color}\",
                    \"title\": \"Deployment Rollback - PR #${PR_NUMBER}\",
                    \"text\": \"${message}\",
                    \"fields\": [
                        {\"title\": \"Environment\", \"value\": \"${ENVIRONMENT}\", \"short\": true},
                        {\"title\": \"Type\", \"value\": \"${DEPLOYMENT_TYPE}\", \"short\": true}
                    ],
                    \"timestamp\": $(date +%s)
                }]
            }" > /dev/null
    fi
}

# Health check function
check_health() {
    local url=$1
    local max_attempts=5
    local attempt=1
    
    while [ $attempt -le $max_attempts ]; do
        log INFO "Health check attempt ${attempt}/${max_attempts}"
        
        if curl -sf "${url}" > /dev/null 2>&1; then
            log SUCCESS "Health check passed"
            return 0
        fi
        
        sleep 5
        ((attempt++))
    done
    
    log ERROR "Health check failed after ${max_attempts} attempts"
    return 1
}

# Get deployment history
get_deployment_history() {
    case $DEPLOYMENT_TYPE in
        kubernetes)
            kubectl rollout history deployment/app -n "${ENVIRONMENT}" 2>/dev/null || echo "No history available"
            ;;
        helm)
            helm history app-"${ENVIRONMENT}" 2>/dev/null || echo "No history available"
            ;;
        docker)
            docker service ls --filter "name=app" 2>/dev/null || echo "No services found"
            ;;
    esac
}

# Perform rollback based on deployment type
perform_rollback() {
    log INFO "Starting rollback for ${DEPLOYMENT_TYPE} deployment in ${ENVIRONMENT}"
    
    case $DEPLOYMENT_TYPE in
        kubernetes)
            rollback_kubernetes
            ;;
        helm)
            rollback_helm
            ;;
        docker)
            rollback_docker
            ;;
        *)
            log ERROR "Unknown deployment type: ${DEPLOYMENT_TYPE}"
            return 1
            ;;
    esac
}

# Kubernetes rollback
rollback_kubernetes() {
    log INFO "Performing Kubernetes rollback"
    
    # Get current revision
    local current_revision=$(kubectl rollout history deployment/app -n "${ENVIRONMENT}" | tail -2 | head -1 | awk '{print $1}')
    log INFO "Current revision: ${current_revision}"
    
    # Perform rollback
    if kubectl rollout undo deployment/app -n "${ENVIRONMENT}"; then
        log SUCCESS "Rollback command executed successfully"
        
        # Wait for rollout to complete
        log INFO "Waiting for rollout to complete..."
        if kubectl rollout status deployment/app -n "${ENVIRONMENT}" --timeout=5m; then
            log SUCCESS "Rollback completed successfully"
            
            # Verify pods are running
            local ready_pods=$(kubectl get deployment app -n "${ENVIRONMENT}" -o jsonpath='{.status.readyReplicas}')
            local desired_pods=$(kubectl get deployment app -n "${ENVIRONMENT}" -o jsonpath='{.spec.replicas}')
            
            if [[ "$ready_pods" == "$desired_pods" ]]; then
                log SUCCESS "All pods are ready (${ready_pods}/${desired_pods})"
                return 0
            else
                log WARNING "Not all pods are ready (${ready_pods}/${desired_pods})"
                return 1
            fi
        else
            log ERROR "Rollback status check failed"
            return 1
        fi
    else
        log ERROR "Failed to execute rollback command"
        return 1
    fi
}

# Helm rollback
rollback_helm() {
    log INFO "Performing Helm rollback"
    
    local release_name="app-${ENVIRONMENT}"
    
    # Get current revision
    local current_revision=$(helm list -n "${ENVIRONMENT}" | grep "${release_name}" | awk '{print $3}')
    log INFO "Current revision: ${current_revision}"
    
    # Rollback to previous revision
    if helm rollback "${release_name}" 0 -n "${ENVIRONMENT}"; then
        log SUCCESS "Helm rollback executed successfully"
        
        # Verify rollback
        sleep 10
        local new_revision=$(helm list -n "${ENVIRONMENT}" | grep "${release_name}" | awk '{print $3}')
        
        if [[ "$new_revision" != "$current_revision" ]]; then
            log SUCCESS "Rollback verified - new revision: ${new_revision}"
            return 0
        else
            log ERROR "Rollback verification failed"
            return 1
        fi
    else
        log ERROR "Failed to execute Helm rollback"
        return 1
    fi
}

# Docker rollback
rollback_docker() {
    log INFO "Performing Docker service rollback"
    
    local service_name="app-${ENVIRONMENT}"
    
    # Get current task state
    local current_tasks=$(docker service ps "${service_name}" --format "table {{.Name}}\t{{.CurrentState}}")
    log INFO "Current service state:\n${current_tasks}"
    
    # Perform rollback
    if docker service rollback "${service_name}"; then
        log SUCCESS "Docker service rollback executed"
        
        # Wait for service to stabilize
        sleep 15
        
        # Check service state
        local running_tasks=$(docker service ps "${service_name}" --filter "desired-state=running" --format "{{.CurrentState}}" | grep -c "Running")
        local desired_replicas=$(docker service inspect "${service_name}" --format "{{.Spec.Mode.Replicated.Replicas}}")
        
        if [[ "$running_tasks" -ge "$desired_replicas" ]]; then
            log SUCCESS "Service rollback completed (${running_tasks}/${desired_replicas} tasks running)"
            return 0
        else
            log ERROR "Service not fully recovered (${running_tasks}/${desired_replicas} tasks running)"
            return 1
        fi
    else
        log ERROR "Failed to execute Docker service rollback"
        return 1
    fi
}

# Create rollback report
create_rollback_report() {
    local status=$1
    local start_time=$2
    local end_time=$3
    
    local report_file="rollback-report-${PR_NUMBER}-$(date +%Y%m%d-%H%M%S).json"
    
    cat > "$report_file" <<EOF
{
    "pr_number": "${PR_NUMBER}",
    "environment": "${ENVIRONMENT}",
    "deployment_type": "${DEPLOYMENT_TYPE}",
    "status": "${status}",
    "forced": ${FORCE_ROLLBACK},
    "start_time": "${start_time}",
    "end_time": "${end_time}",
    "duration_seconds": $((end_time - start_time)),
    "health_check_url": "${HEALTH_CHECK_URL}",
    "deployment_history": $(get_deployment_history | jq -Rs .),
    "timestamp": "$(date -Iseconds)"
}
EOF
    
    log INFO "Rollback report saved to: ${report_file}"
    return 0
}

# Update GitHub PR
update_github_pr() {
    local status=$1
    local message=$2
    
    if [[ -n "$GITHUB_TOKEN" ]]; then
        local comment="## 🔄 Rollback Report

**Status**: ${status}
**Environment**: ${ENVIRONMENT}
**Deployment Type**: ${DEPLOYMENT_TYPE}

${message}

*Automated rollback executed at $(date)*"
        
        # Post comment to PR (assuming you have the repo name)
        # This would need the actual repo name to work
        log INFO "Updating GitHub PR #${PR_NUMBER}"
    fi
}

# Main execution
main() {
    local start_time=$(date +%s)
    
    log INFO "=== Starting Rollback Process ==="
    log INFO "PR Number: ${PR_NUMBER}"
    log INFO "Environment: ${ENVIRONMENT}"
    log INFO "Deployment Type: ${DEPLOYMENT_TYPE}"
    log INFO "Force Rollback: ${FORCE_ROLLBACK}"
    
    # Check current health status if not forcing
    if [[ "$FORCE_ROLLBACK" != "true" ]] && [[ -n "$HEALTH_CHECK_URL" ]]; then
        log INFO "Checking current health status..."
        if check_health "$HEALTH_CHECK_URL"; then
            log WARNING "Application appears healthy - rollback may not be necessary"
            read -p "Continue with rollback? (y/n) " -n 1 -r
            echo
            if [[ ! $REPLY =~ ^[Yy]$ ]]; then
                log INFO "Rollback cancelled by user"
                exit 0
            fi
        fi
    fi
    
    # Send notification about rollback start
    send_notification "warning" "Rollback initiated for PR #${PR_NUMBER} in ${ENVIRONMENT}"
    
    # Perform the rollback
    if perform_rollback; then
        log SUCCESS "Rollback completed successfully"
        
        # Verify health after rollback
        if [[ -n "$HEALTH_CHECK_URL" ]]; then
            log INFO "Verifying application health after rollback..."
            if check_health "$HEALTH_CHECK_URL"; then
                log SUCCESS "Application is healthy after rollback"
                local status="SUCCESS"
                local message="Rollback completed successfully and application is healthy"
            else
                log WARNING "Application health check failed after rollback"
                local status="WARNING"
                local message="Rollback completed but health check failed"
            fi
        else
            local status="SUCCESS"
            local message="Rollback completed (no health check URL provided)"
        fi
    else
        log ERROR "Rollback failed"
        local status="FAILED"
        local message="Rollback procedure failed - manual intervention required"
    fi
    
    local end_time=$(date +%s)
    
    # Create rollback report
    create_rollback_report "$status" "$start_time" "$end_time"
    
    # Update GitHub PR
    update_github_pr "$status" "$message"
    
    # Send final notification
    send_notification "${status,,}" "$message"
    
    log INFO "=== Rollback Process Complete ==="
    
    # Exit with appropriate code
    if [[ "$status" == "SUCCESS" ]]; then
        exit 0
    else
        exit 1
    fi
}

# Run main function
main
