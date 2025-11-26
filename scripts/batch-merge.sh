#!/bin/bash

# Batch Merge Script for Dependabot PRs
# This script can process multiple Dependabot PRs in batch based on criteria

set -e

# Configuration
REPO_NAME=""
GITHUB_TOKEN="${GITHUB_TOKEN}"
UPDATE_TYPE="patch"  # patch, minor, all
STATUS="approved"     # approved, all
MAX_PRS=10
DRY_RUN=false
MERGE_METHOD="squash" # squash, merge, rebase
DELAY_BETWEEN_MERGES=5  # seconds

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --repo)
            REPO_NAME="$2"
            shift 2
            ;;
        --type)
            UPDATE_TYPE="$2"
            shift 2
            ;;
        --status)
            STATUS="$2"
            shift 2
            ;;
        --max)
            MAX_PRS="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --merge-method)
            MERGE_METHOD="$2"
            shift 2
            ;;
        --delay)
            DELAY_BETWEEN_MERGES="$2"
            shift 2
            ;;
        --help)
            cat <<EOF
Usage: $0 --repo REPO_NAME [OPTIONS]

Options:
    --repo REPO_NAME       Repository name (required)
    --type TYPE            Update type to merge (patch|minor|all) [default: patch]
    --status STATUS        PR status filter (approved|all) [default: approved]
    --max NUMBER           Maximum PRs to process [default: 10]
    --merge-method METHOD  Merge method (squash|merge|rebase) [default: squash]
    --delay SECONDS        Delay between merges [default: 5]
    --dry-run             Preview without merging
    --help                Show this help message

Examples:
    # Merge all approved patch updates
    $0 --repo owner/repo --type patch --status approved

    # Dry run for minor updates
    $0 --repo owner/repo --type minor --dry-run

    # Merge up to 20 PRs of any type
    $0 --repo owner/repo --type all --max 20
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
if [[ -z "$REPO_NAME" ]]; then
    echo -e "${RED}Error: Repository name is required${NC}"
    echo "Use --help for usage information"
    exit 1
fi

# Function to make GitHub API calls
github_api() {
    local method=$1
    local endpoint=$2
    local data=$3
    
    if [[ -n "$data" ]]; then
        curl -s -X "$method" \
            -H "Authorization: token ${GITHUB_TOKEN}" \
            -H "Accept: application/vnd.github.v3+json" \
            "https://api.github.com${endpoint}" \
            -d "$data"
    else
        curl -s -X "$method" \
            -H "Authorization: token ${GITHUB_TOKEN}" \
            -H "Accept: application/vnd.github.v3+json" \
            "https://api.github.com${endpoint}"
    fi
}

# Function to check if PR is eligible for batch merge
is_eligible_for_merge() {
    local pr_data=$1
    local pr_number=$(echo "$pr_data" | jq -r '.number')
    local pr_labels=$(echo "$pr_data" | jq -r '.labels[].name' | tr '\n' ' ')
    local pr_mergeable=$(echo "$pr_data" | jq -r '.mergeable')
    local pr_state=$(echo "$pr_data" | jq -r '.state')
    
    # Check if PR is open
    if [[ "$pr_state" != "open" ]]; then
        echo "false"
        return
    fi
    
    # Check if mergeable
    if [[ "$pr_mergeable" == "false" ]]; then
        echo "false"
        return
    fi
    
    # Check update type if specified
    if [[ "$UPDATE_TYPE" != "all" ]]; then
        if [[ ! "$pr_labels" =~ "${UPDATE_TYPE}-update" ]]; then
            echo "false"
            return
        fi
    fi
    
    # Check if PR has required labels
    if [[ "$pr_labels" =~ "do-not-merge" ]] || [[ "$pr_labels" =~ "work-in-progress" ]]; then
        echo "false"
        return
    fi
    
    # Check approval status if required
    if [[ "$STATUS" == "approved" ]]; then
        local reviews=$(github_api GET "/repos/${REPO_NAME}/pulls/${pr_number}/reviews")
        local approved=$(echo "$reviews" | jq '[.[] | select(.state == "APPROVED")] | length')
        
        if [[ "$approved" -eq 0 ]]; then
            echo "false"
            return
        fi
    fi
    
    echo "true"
}

# Function to check CI status
check_ci_status() {
    local pr_number=$1
    local pr_sha=$2
    
    local status_data=$(github_api GET "/repos/${REPO_NAME}/commits/${pr_sha}/status")
    local combined_status=$(echo "$status_data" | jq -r '.state')
    
    case "$combined_status" in
        success)
            echo "passed"
            ;;
        pending)
            echo "pending"
            ;;
        failure|error)
            echo "failed"
            ;;
        *)
            echo "unknown"
            ;;
    esac
}

# Function to merge a PR
merge_pr() {
    local pr_number=$1
    local pr_title=$2
    
    if [[ "$DRY_RUN" == "true" ]]; then
        echo -e "${YELLOW}[DRY RUN] Would merge PR #${pr_number}: ${pr_title}${NC}"
        return 0
    fi
    
    echo -e "${BLUE}Merging PR #${pr_number}: ${pr_title}...${NC}"
    
    local merge_data=$(cat <<EOF
{
    "commit_title": "Auto-merge: ${pr_title}",
    "commit_message": "Automatically merged by batch processing script",
    "merge_method": "${MERGE_METHOD}"
}
EOF
    )
    
    local result=$(github_api PUT "/repos/${REPO_NAME}/pulls/${pr_number}/merge" "$merge_data")
    
    if echo "$result" | jq -e '.merged' > /dev/null; then
        echo -e "${GREEN}✓ Successfully merged PR #${pr_number}${NC}"
        return 0
    else
        local error_message=$(echo "$result" | jq -r '.message // "Unknown error"')
        echo -e "${RED}✗ Failed to merge PR #${pr_number}: ${error_message}${NC}"
        return 1
    fi
}

# Main execution
echo -e "${BLUE}=== Batch Merge Processing ===${NC}"
echo -e "Repository: ${REPO_NAME}"
echo -e "Update Type: ${UPDATE_TYPE}"
echo -e "Status Filter: ${STATUS}"
echo -e "Max PRs: ${MAX_PRS}"
echo -e "Merge Method: ${MERGE_METHOD}"
echo -e "Dry Run: ${DRY_RUN}"
echo ""

# Fetch Dependabot PRs
echo -e "${BLUE}Fetching Dependabot PRs...${NC}"
PRS_DATA=$(github_api GET "/repos/${REPO_NAME}/pulls?state=open&per_page=${MAX_PRS}")

# Filter for Dependabot PRs
DEPENDABOT_PRS=$(echo "$PRS_DATA" | jq '[.[] | select(.user.login == "dependabot[bot]" or .user.login == "dependabot-preview[bot]")]')

TOTAL_PRS=$(echo "$DEPENDABOT_PRS" | jq 'length')
echo -e "Found ${TOTAL_PRS} Dependabot PRs"
echo ""

# Process PRs
MERGED_COUNT=0
SKIPPED_COUNT=0
FAILED_COUNT=0

echo "$DEPENDABOT_PRS" | jq -c '.[]' | while read -r pr; do
    PR_NUMBER=$(echo "$pr" | jq -r '.number')
    PR_TITLE=$(echo "$pr" | jq -r '.title')
    PR_SHA=$(echo "$pr" | jq -r '.head.sha')
    
    echo -e "${BLUE}Processing PR #${PR_NUMBER}: ${PR_TITLE}${NC}"
    
    # Check eligibility
    if [[ $(is_eligible_for_merge "$pr") != "true" ]]; then
        echo -e "${YELLOW}→ Skipped: Not eligible for batch merge${NC}"
        ((SKIPPED_COUNT++))
        echo ""
        continue
    fi
    
    # Check CI status
    CI_STATUS=$(check_ci_status "$PR_NUMBER" "$PR_SHA")
    if [[ "$CI_STATUS" != "passed" ]]; then
        echo -e "${YELLOW}→ Skipped: CI status is ${CI_STATUS}${NC}"
        ((SKIPPED_COUNT++))
        echo ""
        continue
    fi
    
    # Merge the PR
    if merge_pr "$PR_NUMBER" "$PR_TITLE"; then
        ((MERGED_COUNT++))
        
        # Delay between merges to avoid overwhelming the system
        if [[ $MERGED_COUNT -lt $TOTAL_PRS ]] && [[ "$DRY_RUN" != "true" ]]; then
            echo -e "Waiting ${DELAY_BETWEEN_MERGES} seconds before next merge..."
            sleep "$DELAY_BETWEEN_MERGES"
        fi
    else
        ((FAILED_COUNT++))
    fi
    
    echo ""
done

# Summary report
echo -e "${BLUE}=== Batch Merge Summary ===${NC}"
echo -e "Total Dependabot PRs: ${TOTAL_PRS}"
echo -e "${GREEN}Merged: ${MERGED_COUNT}${NC}"
echo -e "${YELLOW}Skipped: ${SKIPPED_COUNT}${NC}"
echo -e "${RED}Failed: ${FAILED_COUNT}${NC}"

# Generate detailed report
if [[ "$DRY_RUN" != "true" ]] && [[ $MERGED_COUNT -gt 0 ]]; then
    REPORT_FILE="batch-merge-report-$(date +%Y%m%d-%H%M%S).txt"
    cat > "$REPORT_FILE" <<EOF
Batch Merge Report
==================
Timestamp: $(date)
Repository: ${REPO_NAME}
Update Type: ${UPDATE_TYPE}
Status Filter: ${STATUS}

Results:
- Merged: ${MERGED_COUNT}
- Skipped: ${SKIPPED_COUNT}
- Failed: ${FAILED_COUNT}

Merge Method: ${MERGE_METHOD}
EOF
    
    echo ""
    echo -e "${GREEN}Report saved to: ${REPORT_FILE}${NC}"
fi

# Exit with appropriate code
if [[ $FAILED_COUNT -gt 0 ]]; then
    exit 1
else
    exit 0
fi
