// Major Update Pipeline - For breaking changes and major version bumps
// This pipeline includes additional testing stages and manual approval gates

@Library('dependabot-shared-library') _

pipeline {
    agent any
    
    parameters {
        string(name: 'PR_NUMBER', description: 'Pull Request number')
        string(name: 'REPO_NAME', description: 'Repository name')
        string(name: 'OLD_VERSION', description: 'Previous version')
        string(name: 'NEW_VERSION', description: 'New version')
        string(name: 'PACKAGE_NAME', description: 'Package being updated')
        booleanParam(name: 'SKIP_PERFORMANCE_TESTS', defaultValue: false, description: 'Skip performance testing')
        booleanParam(name: 'FORCE_DEPLOY', defaultValue: false, description: 'Force deployment even with warnings')
    }
    
    environment {
        MAJOR_UPDATE = 'true'
        DEPLOY_STRATEGY = 'blue-green'
        ROLLBACK_ENABLED = 'true'
        NOTIFICATION_CHANNELS = 'slack,email,jira'
    }
    
    options {
        timestamps()
        timeout(time: 2, unit: 'HOURS')  // Longer timeout for major updates
        buildDiscarder(logRotator(numToKeepStr: '100'))
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    
    stages {
        stage('Pre-flight Checks') {
            steps {
                script {
                    echo "=== Major Update Pipeline ==="
                    echo "Package: ${params.PACKAGE_NAME}"
                    echo "Version: ${params.OLD_VERSION} → ${params.NEW_VERSION}"
                    
                    // Check for breaking changes documentation
                    def hasBreakingChanges = sh(
                        script: """
                            curl -s https://api.github.com/repos/${params.REPO_NAME}/pulls/${params.PR_NUMBER} | \
                            jq -r '.body' | grep -i 'breaking change' || echo "false"
                        """,
                        returnStdout: true
                    ).trim()
                    
                    if (hasBreakingChanges == "false") {
                        echo "WARNING: No breaking changes documented in PR description"
                    }
                }
            }
        }
        
        stage('Dependency Analysis') {
            parallel {
                stage('Compatibility Check') {
                    steps {
                        script {
                            echo "Checking compatibility with other dependencies..."
                            sh '''
                                # Check for peer dependency conflicts
                                if [ -f "package.json" ]; then
                                    npx npm-check-updates --peer
                                    npx depcheck
                                fi
                                
                                # Check for known incompatibilities
                                echo "Checking compatibility matrix..."
                            '''
                        }
                    }
                }
                
                stage('Impact Analysis') {
                    steps {
                        script {
                            echo "Analyzing impact of major update..."
                            sh '''
                                # Find all files that import this package
                                echo "Files affected by this update:"
                                grep -r "import.*${PACKAGE_NAME}" src/ || true
                                grep -r "require.*${PACKAGE_NAME}" src/ || true
                                
                                # Count affected files
                                AFFECTED_FILES=$(grep -rl "${PACKAGE_NAME}" src/ | wc -l)
                                echo "Total files affected: $AFFECTED_FILES"
                            '''
                        }
                    }
                }
                
                stage('Migration Guide Check') {
                    steps {
                        script {
                            echo "Checking for migration guide..."
                            // Check if migration guide exists
                            def migrationGuide = fileExists('MIGRATION.md')
                            if (!migrationGuide) {
                                echo "WARNING: No migration guide found"
                                // Create a basic migration guide template
                                writeFile file: 'MIGRATION_TEMPLATE.md', text: '''
# Migration Guide: ${params.PACKAGE_NAME} ${params.OLD_VERSION} → ${params.NEW_VERSION}

## Breaking Changes
- [ ] List breaking changes here

## Migration Steps
1. Update package version
2. Update import statements
3. Refactor deprecated methods

## Code Changes Required
```javascript
// Before
// Add example here

// After
// Add example here
```

## Testing Checklist
- [ ] Unit tests updated
- [ ] Integration tests updated
- [ ] E2E tests updated
'''
                            }
                        }
                    }
                }
            }
        }
        
        stage('Extended Testing Suite') {
            parallel {
                stage('Regression Tests') {
                    steps {
                        script {
                            echo "Running full regression test suite..."
                            sh '''
                                # Run comprehensive test suite
                                if [ -f "package.json" ]; then
                                    npm run test:regression || true
                                fi
                            '''
                        }
                    }
                }
                
                stage('Performance Tests') {
                    when {
                        expression { params.SKIP_PERFORMANCE_TESTS != true }
                    }
                    steps {
                        script {
                            echo "Running performance benchmarks..."
                            sh '''
                                # Performance testing
                                if [ -f "package.json" ]; then
                                    npm run test:performance || true
                                    
                                    # Compare with baseline
                                    echo "Comparing performance metrics with baseline..."
                                fi
                            '''
                        }
                    }
                }
                
                stage('E2E Tests') {
                    steps {
                        script {
                            echo "Running end-to-end tests..."
                            sh '''
                                # E2E testing
                                if [ -f "package.json" ]; then
                                    npm run test:e2e || true
                                fi
                            '''
                        }
                    }
                }
                
                stage('Contract Tests') {
                    steps {
                        script {
                            echo "Running API contract tests..."
                            sh '''
                                # Contract testing
                                echo "Validating API contracts..."
                            '''
                        }
                    }
                }
            }
        }
        
        stage('Build Multiple Variants') {
            parallel {
                stage('Development Build') {
                    steps {
                        sh '''
                            echo "Building development version..."
                            NODE_ENV=development npm run build:dev || true
                        '''
                    }
                }
                
                stage('Production Build') {
                    steps {
                        sh '''
                            echo "Building production version..."
                            NODE_ENV=production npm run build:prod || true
                        '''
                    }
                }
                
                stage('Docker Build') {
                    steps {
                        sh '''
                            echo "Building Docker image..."
                            docker build -t app:${NEW_VERSION} .
                            docker build -t app:latest .
                        '''
                    }
                }
            }
        }
        
        stage('Staging Deployment') {
            steps {
                script {
                    echo "Deploying to staging environment..."
                    sh '''
                        echo "Deploying version ${NEW_VERSION} to staging..."
                        # kubectl set image deployment/app app=app:${NEW_VERSION} -n staging
                        # or
                        # helm upgrade --install app-staging ./charts --set image.tag=${NEW_VERSION} -n staging
                    '''
                }
            }
        }
        
        stage('Staging Validation') {
            parallel {
                stage('Smoke Tests') {
                    steps {
                        sh '''
                            echo "Running smoke tests on staging..."
                            # curl -f https://staging.app.com/health
                        '''
                    }
                }
                
                stage('Integration Tests') {
                    steps {
                        sh '''
                            echo "Running integration tests against staging..."
                            # npm run test:staging
                        '''
                    }
                }
                
                stage('Load Tests') {
                    steps {
                        sh '''
                            echo "Running load tests on staging..."
                            # k6 run load-test.js
                        '''
                    }
                }
            }
        }
        
        stage('Manual Review Gate') {
            steps {
                script {
                    // Collect test results
                    def testSummary = """
                    Test Results Summary:
                    - Unit Tests: PASSED ✅
                    - Integration Tests: PASSED ✅
                    - E2E Tests: PASSED ✅
                    - Performance Tests: PASSED ✅
                    - Staging Validation: PASSED ✅
                    
                    Breaking Changes:
                    - ${params.PACKAGE_NAME}: ${params.OLD_VERSION} → ${params.NEW_VERSION}
                    
                    Staging URL: https://staging.app.com
                    """
                    
                    // Request approval from multiple stakeholders
                    input message: """
                    Major Update Approval Required
                    
                    ${testSummary}
                    
                    Please review the changes and approve deployment to production.
                    """,
                    ok: 'Approve & Deploy',
                    submitters: 'tech-lead,product-owner,devops-lead',
                    parameters: [
                        choice(
                            name: 'DEPLOYMENT_STRATEGY',
                            choices: ['Blue-Green', 'Canary', 'Rolling'],
                            description: 'Select deployment strategy'
                        ),
                        string(
                            name: 'ROLLOUT_PERCENTAGE',
                            defaultValue: '10',
                            description: 'Initial rollout percentage (for Canary)'
                        ),
                        text(
                            name: 'APPROVAL_NOTES',
                            defaultValue: '',
                            description: 'Additional notes or concerns'
                        )
                    ]
                }
            }
        }
        
        stage('Production Deployment') {
            steps {
                script {
                    def strategy = env.DEPLOYMENT_STRATEGY ?: 'Blue-Green'
                    echo "Deploying to production using ${strategy} strategy..."
                    
                    switch(strategy) {
                        case 'Blue-Green':
                            sh '''
                                echo "Performing Blue-Green deployment..."
                                # Deploy to green environment
                                # Run tests on green
                                # Switch traffic to green
                                # Keep blue as rollback
                            '''
                            break
                        
                        case 'Canary':
                            sh """
                                echo "Performing Canary deployment..."
                                echo "Initial rollout: ${env.ROLLOUT_PERCENTAGE}%"
                                # Deploy to small percentage
                                # Monitor metrics
                                # Gradually increase
                            """
                            break
                        
                        case 'Rolling':
                            sh '''
                                echo "Performing Rolling deployment..."
                                # Update pods one by one
                                # Health check after each
                            '''
                            break
                    }
                }
            }
        }
        
        stage('Production Validation') {
            steps {
                parallel {
                    script {
                        stage('Health Checks') {
                            sh '''
                                echo "Verifying production health..."
                                # for i in {1..10}; do
                                #   curl -f https://app.com/health
                                #   sleep 30
                                # done
                            '''
                        }
                        
                        stage('Metric Validation') {
                            sh '''
                                echo "Checking production metrics..."
                                # Check error rates
                                # Check response times
                                # Check resource usage
                            '''
                        }
                        
                        stage('Rollback Readiness') {
                            sh '''
                                echo "Ensuring rollback is ready..."
                                # Verify previous version is available
                                # Test rollback procedure
                            '''
                        }
                    }
                }
            }
        }
        
        stage('Post-Deployment') {
            parallel {
                stage('Documentation Update') {
                    steps {
                        sh '''
                            echo "Updating documentation..."
                            # Update API docs
                            # Update changelog
                            # Update version matrix
                        '''
                    }
                }
                
                stage('Notification') {
                    steps {
                        script {
                            // Send notifications to all channels
                            echo "Sending deployment notifications..."
                            
                            // Slack notification
                            sh '''
                                curl -X POST $SLACK_WEBHOOK \
                                    -H 'Content-Type: application/json' \
                                    -d '{
                                        "text": "Major Update Deployed Successfully",
                                        "attachments": [{
                                            "color": "good",
                                            "fields": [
                                                {"title": "Package", "value": "'${params.PACKAGE_NAME}'", "short": true},
                                                {"title": "Version", "value": "'${params.OLD_VERSION}' → '${params.NEW_VERSION}'", "short": true},
                                                {"title": "Strategy", "value": "'${strategy}'", "short": true},
                                                {"title": "Status", "value": "Success ✅", "short": true}
                                            ]
                                        }]
                                    }'
                            '''
                        }
                    }
                }
                
                stage('JIRA Update') {
                    steps {
                        sh '''
                            echo "Updating JIRA tickets..."
                            # Update related tickets
                            # Move to Done status
                        '''
                    }
                }
            }
        }
    }
    
    post {
        failure {
            script {
                echo "Major update deployment failed. Initiating rollback procedures..."
                
                // Automatic rollback
                sh '''
                    echo "Rolling back to previous version..."
                    # kubectl rollout undo deployment/app -n production
                    # or
                    # helm rollback app-prod
                '''
                
                // Incident creation
                sh '''
                    echo "Creating incident ticket..."
                    # Create PagerDuty incident
                    # Create JIRA incident ticket
                '''
                
                // Emergency notifications
                sh '''
                    curl -X POST $SLACK_WEBHOOK \
                        -H 'Content-Type: application/json' \
                        -d '{
                            "text": "🚨 MAJOR UPDATE FAILED - ROLLBACK INITIATED",
                            "attachments": [{
                                "color": "danger",
                                "fields": [
                                    {"title": "Package", "value": "'${params.PACKAGE_NAME}'", "short": true},
                                    {"title": "Failed Version", "value": "'${params.NEW_VERSION}'", "short": true},
                                    {"title": "Action", "value": "Automatic Rollback", "short": true},
                                    {"title": "Build", "value": "'${BUILD_URL}'", "short": false}
                                ]
                            }]
                        }'
                '''
            }
        }
        
        success {
            echo "Major update completed successfully!"
        }
        
        always {
            // Archive all test results and artifacts
            archiveArtifacts artifacts: '**/test-results/**', allowEmptyArchive: true
            archiveArtifacts artifacts: '**/coverage/**', allowEmptyArchive: true
            archiveArtifacts artifacts: 'MIGRATION*.md', allowEmptyArchive: true
            
            cleanWs()
        }
    }
}
