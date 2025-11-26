#!/bin/bash

# Categorize Updates Script
# This script analyzes Dependabot PRs and categorizes them based on various criteria

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
PR_NUMBER=""
REPO_NAME=""
GITHUB_TOKEN="${GITHUB_TOKEN}"
OUTPUT_FORMAT="json"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --pr)
            PR_NUMBER="$2"
            shift 2
            ;;
        --repo)
            REPO_NAME="$2"
            shift 2
            ;;
        --token)
            GITHUB_TOKEN="$2"
            shift 2
            ;;
        --format)
            OUTPUT_FORMAT="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 --pr PR_NUMBER --repo REPO_NAME [--token TOKEN] [--format json|text]"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Validate required parameters
if [[ -z "$PR_NUMBER" ]] || [[ -z "$REPO_NAME" ]]; then
    echo -e "${RED}Error: PR number and repository name are required${NC}"
    echo "Usage: $0 --pr PR_NUMBER --repo REPO_NAME [--token TOKEN]"
    exit 1
fi

# Function to make GitHub API calls
github_api() {
    local endpoint=$1
    curl -s -H "Authorization: token ${GITHUB_TOKEN}" \
         -H "Accept: application/vnd.github.v3+json" \
         "https://api.github.com${endpoint}"
}

# Function to extract version from string
extract_version() {
    echo "$1" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
}

# Function to determine update type
determine_update_type() {
    local old_version=$1
    local new_version=$2
    
    IFS='.' read -r -a old_parts <<< "$old_version"
    IFS='.' read -r -a new_parts <<< "$new_version"
    
    if [[ "${old_parts[0]}" != "${new_parts[0]}" ]]; then
        echo "major"
    elif [[ "${old_parts[1]}" != "${new_parts[1]}" ]]; then
        echo "minor"
    else
        echo "patch"
    fi
}

# Function to calculate risk score
calculate_risk_score() {
    local update_type=$1
    local is_security=$2
    local package_name=$3
    local ecosystem=$4
    
    local risk_score=0
    
    # Base score by update type
    case $update_type in
        major)
            risk_score=$((risk_score + 70))
            ;;
        minor)
            risk_score=$((risk_score + 30))
            ;;
        patch)
            risk_score=$((risk_score + 10))
            ;;
    esac
    
    # Adjust for security updates
    if [[ "$is_security" == "true" ]]; then
        if [[ "$update_type" == "patch" ]]; then
            risk_score=$((risk_score - 5))
        else
            risk_score=$((risk_score + 10))
        fi
    fi
    
    # Critical packages get higher risk scores
    critical_packages=("react" "express" "django" "rails" "spring" "angular" "vue")
    for pkg in "${critical_packages[@]}"; do
        if [[ "$package_name" == *"$pkg"* ]]; then
            risk_score=$((risk_score + 20))
            break
        fi
    done
    
    # Infrastructure packages are high risk
    if [[ "$ecosystem" == "docker" ]] || [[ "$package_name" == *"kubernetes"* ]]; then
        risk_score=$((risk_score + 15))
    fi
    
    # Cap at 100
    if [[ $risk_score -gt 100 ]]; then
        risk_score=100
    fi
    
    echo $risk_score
}

# Main execution
echo -e "${BLUE}Analyzing PR #${PR_NUMBER} from ${REPO_NAME}...${NC}"

# Fetch PR data
PR_DATA=$(github_api "/repos/${REPO_NAME}/pulls/${PR_NUMBER}")

if [[ -z "$PR_DATA" ]] || [[ "$PR_DATA" == *"Not Found"* ]]; then
    echo -e "${RED}Error: Could not fetch PR data${NC}"
    exit 1
fi

# Extract PR information
PR_TITLE=$(echo "$PR_DATA" | jq -r '.title')
PR_BODY=$(echo "$PR_DATA" | jq -r '.body')
PR_LABELS=$(echo "$PR_DATA" | jq -r '.labels[].name' | tr '\n' ',')
PR_USER=$(echo "$PR_DATA" | jq -r '.user.login')

# Check if it's a Dependabot PR
if [[ "$PR_USER" != "dependabot[bot]" ]] && [[ "$PR_USER" != "dependabot-preview[bot]" ]]; then
    echo -e "${YELLOW}Warning: This is not a Dependabot PR (user: $PR_USER)${NC}"
fi

# Extract version information
OLD_VERSION=$(echo "$PR_TITLE" | sed -n 's/.*from \([0-9.]*\).*/\1/p')
NEW_VERSION=$(echo "$PR_TITLE" | sed -n 's/.*to \([0-9.]*\).*/\1/p')

# Extract package name
PACKAGE_NAME=$(echo "$PR_TITLE" | sed -n 's/Bump \(.*\) from.*/\1/p')

# Determine update type
UPDATE_TYPE=""
if [[ -n "$OLD_VERSION" ]] && [[ -n "$NEW_VERSION" ]]; then
    UPDATE_TYPE=$(determine_update_type "$OLD_VERSION" "$NEW_VERSION")
else
    echo -e "${YELLOW}Could not determine version change, checking labels...${NC}"
    if [[ "$PR_LABELS" == *"dependencies"* ]]; then
        UPDATE_TYPE="unknown"
    fi
fi

# Check if it's a security update
IS_SECURITY="false"
if [[ "$PR_LABELS" == *"security"* ]] || [[ "$PR_BODY" == *"security"* ]] || [[ "$PR_BODY" == *"vulnerability"* ]]; then
    IS_SECURITY="true"
fi

# Detect ecosystem
ECOSYSTEM="unknown"
FILES_DATA=$(github_api "/repos/${REPO_NAME}/pulls/${PR_NUMBER}/files")

if echo "$FILES_DATA" | grep -q "package.json\|package-lock.json"; then
    ECOSYSTEM="npm"
elif echo "$FILES_DATA" | grep -q "requirements.txt\|Pipfile\|setup.py"; then
    ECOSYSTEM="pip"
elif echo "$FILES_DATA" | grep -q "pom.xml"; then
    ECOSYSTEM="maven"
elif echo "$FILES_DATA" | grep -q "build.gradle"; then
    ECOSYSTEM="gradle"
elif echo "$FILES_DATA" | grep -q "go.mod"; then
    ECOSYSTEM="go"
elif echo "$FILES_DATA" | grep -q "Gemfile"; then
    ECOSYSTEM="bundler"
elif echo "$FILES_DATA" | grep -q "Dockerfile"; then
    ECOSYSTEM="docker"
elif echo "$FILES_DATA" | grep -q ".github/workflows"; then
    ECOSYSTEM="github-actions"
fi

# Calculate risk score
RISK_SCORE=$(calculate_risk_score "$UPDATE_TYPE" "$IS_SECURITY" "$PACKAGE_NAME" "$ECOSYSTEM")

# Determine risk level
RISK_LEVEL="minimal"
if [[ $RISK_SCORE -ge 70 ]]; then
    RISK_LEVEL="high"
elif [[ $RISK_SCORE -ge 40 ]]; then
    RISK_LEVEL="medium"
elif [[ $RISK_SCORE -ge 20 ]]; then
    RISK_LEVEL="low"
fi

# Determine if auto-merge is eligible
AUTO_MERGE_ELIGIBLE="false"
if [[ "$UPDATE_TYPE" == "patch" ]] && [[ $RISK_SCORE -lt 30 ]]; then
    AUTO_MERGE_ELIGIBLE="true"
elif [[ "$UPDATE_TYPE" == "minor" ]] && [[ $RISK_SCORE -lt 40 ]] && [[ "$IS_SECURITY" == "true" ]]; then
    AUTO_MERGE_ELIGIBLE="true"
fi

# Generate categorization report
if [[ "$OUTPUT_FORMAT" == "json" ]]; then
    cat <<EOF
{
  "pr_number": ${PR_NUMBER},
  "package_name": "${PACKAGE_NAME}",
  "old_version": "${OLD_VERSION}",
  "new_version": "${NEW_VERSION}",
  "update_type": "${UPDATE_TYPE}",
  "ecosystem": "${ECOSYSTEM}",
  "is_security": ${IS_SECURITY},
  "risk_score": ${RISK_SCORE},
  "risk_level": "${RISK_LEVEL}",
  "auto_merge_eligible": ${AUTO_MERGE_ELIGIBLE},
  "labels": "${PR_LABELS}",
  "categorization": {
    "primary": "${UPDATE_TYPE}-update",
    "secondary": "${ECOSYSTEM}",
    "priority": "$(if [[ "$IS_SECURITY" == "true" ]]; then echo "high"; elif [[ "$UPDATE_TYPE" == "major" ]]; then echo "medium"; else echo "low"; fi)",
    "tags": [
      "${UPDATE_TYPE}",
      "${ECOSYSTEM}",
      "${RISK_LEVEL}-risk"$(if [[ "$IS_SECURITY" == "true" ]]; then echo ', "security"'; fi)$(if [[ "$AUTO_MERGE_ELIGIBLE" == "true" ]]; then echo ', "auto-merge-candidate"'; fi)
    ]
  },
  "recommendations": {
    "merge_strategy": "$(if [[ "$UPDATE_TYPE" == "major" ]]; then echo "manual-review"; else echo "auto-merge-after-checks"; fi)",
    "deployment_strategy": "$(if [[ $RISK_SCORE -ge 50 ]]; then echo "canary"; else echo "rolling"; fi)",
    "testing_requirements": [
      "unit-tests"$(if [[ "$UPDATE_TYPE" == "major" ]]; then echo ', "integration-tests", "e2e-tests"'; fi)$(if [[ "$IS_SECURITY" == "true" ]]; then echo ', "security-scan"'; fi)
    ]
  }
}
EOF
else
    echo -e "${GREEN}=== Categorization Report ===${NC}"
    echo -e "PR Number: ${PR_NUMBER}"
    echo -e "Package: ${PACKAGE_NAME}"
    echo -e "Version: ${OLD_VERSION} → ${NEW_VERSION}"
    echo -e "Update Type: ${UPDATE_TYPE}"
    echo -e "Ecosystem: ${ECOSYSTEM}"
    echo -e "Security Update: ${IS_SECURITY}"
    echo -e "Risk Score: ${RISK_SCORE}/100"
    echo -e "Risk Level: ${RISK_LEVEL}"
    echo -e "Auto-Merge Eligible: ${AUTO_MERGE_ELIGIBLE}"
    echo -e ""
    echo -e "${BLUE}Recommendations:${NC}"
    echo -e "- Merge Strategy: $(if [[ "$UPDATE_TYPE" == "major" ]]; then echo "Manual Review Required"; else echo "Can Auto-Merge After Checks"; fi)"
    echo -e "- Deployment: $(if [[ $RISK_SCORE -ge 50 ]]; then echo "Use Canary Deployment"; else echo "Standard Rolling Update"; fi)"
    echo -e "- Testing: $(if [[ "$UPDATE_TYPE" == "major" ]]; then echo "Full Test Suite Required"; else echo "Standard Tests"; fi)"
fi

# Add labels to PR if we have permission
if [[ -n "$GITHUB_TOKEN" ]]; then
    echo -e "\n${BLUE}Applying labels to PR...${NC}"
    
    # Prepare labels
    LABELS_TO_ADD="\"${UPDATE_TYPE}-update\", \"${ECOSYSTEM}\", \"${RISK_LEVEL}-risk\""
    if [[ "$IS_SECURITY" == "true" ]]; then
        LABELS_TO_ADD="${LABELS_TO_ADD}, \"security\""
    fi
    if [[ "$AUTO_MERGE_ELIGIBLE" == "true" ]]; then
        LABELS_TO_ADD="${LABELS_TO_ADD}, \"auto-merge-candidate\""
    fi
    
    # Apply labels
    curl -s -X POST \
        -H "Authorization: token ${GITHUB_TOKEN}" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/${REPO_NAME}/issues/${PR_NUMBER}/labels" \
        -d "{\"labels\": [${LABELS_TO_ADD}]}" > /dev/null
    
    echo -e "${GREEN}Labels applied successfully${NC}"
fi

exit 0
