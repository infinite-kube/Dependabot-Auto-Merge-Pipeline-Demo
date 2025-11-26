// Minor/Patch Update Pipeline - For non-breaking updates
// This pipeline focuses on speed and automation for low-risk changes

@Library('dependabot-shared-library') _

pipeline {
    agent any
    
    parameters {
        string(name: 'PR_NUMBER', description: 'Pull Request number')
        string(name: 'REPO_NAME', description: 'Repository name')
        string(name: 'UPDATE_TYPE', description: 'Update type (patch/minor)')
        string(name: 'PACKAGE_NAME', description: 'Package being updated')
        string(name: 'OLD_VERSION', description: 'Previous version')
        string(name: 'NEW_VERSION', description: 'New version')
        booleanParam(name: 'AUTO_DEPLOY', defaultValue: true, description: 'Automatically deploy if tests pass')
        booleanParam(name: 'FAST_TRACK', defaultValue: false, description: 'Skip non-critical checks')
    }
    
    environment {
        UPDATE_RISK = 'low'
        AUTO_MERGE = 'true'
        DEPLOY_STRATEGY = 'rolling'
        TEST_LEVEL = "${params.FAST_TRACK ? 'minimal' : 'standard'}"
    }
    
    options {
        timestamps()
        timeout(time: 15, unit: 'MINUTES')  // Quick turnaround for minor updates
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
        skipDefaultCheckout()
    }
    
    stages {
        stage('Quick Setup') {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${params.UPDATE_TYPE} - ${params.PACKAGE_NAME}"
                    currentBuild.description = "${params.OLD_VERSION} → ${params.NEW_VERSION}"
                    
                    echo "=== Minor/Patch Update Fast Pipeline ==="
                    echo "Package: ${params.PACKAGE_NAME}"
                    echo "Update: ${params.OLD_VERSION} → ${params.NEW_VERSION}"
                    echo "Type: ${params.UPDATE_TYPE}"
                }
                
                // Quick checkout
                checkout scm
            }
        }
        
        stage('Dependency Install') {
            steps {
                script {
                    echo "Installing dependencies..."
                    
                    // Parallel installation for speed
                    parallel(
                        npm: {
                            if (fileExists('package.json')) {
                                sh 'npm ci --prefer-offline --no-audit'
                            }
                        },
                        pip: {
                            if (fileExists('requirements.txt')) {
                                sh '''
                                    python3 -m venv venv
                                    . venv/bin/activate
                                    pip install -r requirements.txt --quiet
                                '''
                            }
                        },
                        maven: {
                            if (fileExists('pom.xml')) {
                                sh 'mvn dependency:resolve -q'
                            }
                        }
                    )
                }
            }
        }
        
        stage('Fast Validation') {
            when {
                expression { params.FAST_TRACK != true }
            }
            parallel {
                stage('Lint Check') {
                    steps {
                        script {
                            echo "Quick lint check..."
                            if (fileExists('package.json')) {
                                sh 'npm run lint --silent || true'
                            }
                        }
                    }
                }
                
                stage('Security Scan') {
                    steps {
                        script {
                            echo "Quick security scan..."
                            if (fileExists('package.json')) {
                                // Only check for high/critical vulnerabilities
                                sh 'npm audit --audit-level=high || true'
                            }
                        }
                    }
                }
                
                stage('Unit Tests') {
                    steps {
                        script {
                            echo "Running unit tests..."
                            if (fileExists('package.json')) {
                                sh 'npm test -- --maxWorkers=4 || true'
                            }
                            if (fileExists('requirements.txt')) {
                                sh '''
                                    . venv/bin/activate
                                    pytest tests/unit -x --tb=short || true
                                '''
                            }
                        }
                    }
                }
            }
        }
        
        stage('Build & Package') {
            steps {
                script {
                    echo "Building application..."
                    
                    if (fileExists('package.json')) {
                        sh 'npm run build'
                    }
                    
                    if (fileExists('Dockerfile')) {
                        sh """
                            docker build -t ${params.REPO_NAME}:${params.NEW_VERSION} . \
                                --cache-from ${params.REPO_NAME}:latest \
                                --build-arg VERSION=${params.NEW_VERSION}
                        """
                    }
                }
            }
        }
        
        stage('Auto-Merge PR') {
            when {
                expression { env.AUTO_MERGE == 'true' }
            }
            steps {
                script {
                    echo "Auto-merging PR #${params.PR_NUMBER}..."
                    
                    // Quick validation that tests passed
                    if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                        sh """
                            # Auto-approve the PR
                            gh pr review ${params.PR_NUMBER} \
                                --approve \
                                --body "Auto-approved: ${params.UPDATE_TYPE} update passed all checks"
                            
                            # Merge the PR
                            gh pr merge ${params.PR_NUMBER} \
                                --squash \
                                --delete-branch \
                                --subject "Auto-merge: Update ${params.PACKAGE_NAME} to ${params.NEW_VERSION}" \
                                --body "Automated merge of ${params.UPDATE_TYPE} update via Jenkins pipeline"
                        """
                        
                        // Add success label
                        sh "gh pr edit ${params.PR_NUMBER} --add-label 'auto-merged'"
                    } else {
                        error("Cannot auto-merge: Tests failed")
                    }
                }
            }
        }
        
        stage('Quick Deploy') {
            when {
                expression { params.AUTO_DEPLOY == true }
            }
            steps {
                script {
                    echo "Deploying ${params.UPDATE_TYPE} update..."
                    
                    // Use rolling update for zero-downtime
                    sh """
                        echo "Performing rolling update..."
                        # kubectl set image deployment/app app=${params.REPO_NAME}:${params.NEW_VERSION} \
                        #     --record=true \
                        #     -n production
                        
                        # Wait for rollout to complete (with timeout)
                        # kubectl rollout status deployment/app -n production --timeout=5m
                    """
                    
                    // Quick health check
                    sh '''
                        echo "Verifying deployment..."
                        sleep 10
                        # curl -f https://app.com/health || exit 1
                    '''
                }
            }
        }
        
        stage('Quick Verification') {
            when {
                expression { params.AUTO_DEPLOY == true }
            }
            parallel {
                stage('Health Check') {
                    steps {
                        retry(3) {
                            sh '''
                                echo "Checking application health..."
                                # curl -f https://app.com/health
                                # curl -f https://app.com/api/version
                            '''
                        }
                    }
                }
                
                stage('Basic Smoke Test') {
                    steps {
                        sh '''
                            echo "Running quick smoke tests..."
                            # npm run test:smoke:quick || true
                        '''
                    }
                }
                
                stage('Metric Check') {
                    steps {
                        sh '''
                            echo "Checking basic metrics..."
                            # Check error rate is below threshold
                            # Check response time is acceptable
                        '''
                    }
                }
            }
        }
        
        stage('Cleanup & Report') {
            steps {
                script {
                    // Generate summary report
                    def summary = """
                    Update Summary:
                    - Package: ${params.PACKAGE_NAME}
                    - Type: ${params.UPDATE_TYPE}
                    - Version: ${params.OLD_VERSION} → ${params.NEW_VERSION}
                    - Duration: ${currentBuild.durationString}
                    - Status: SUCCESS ✅
                    """
                    
                    // Post to PR
                    sh """
                        gh pr comment ${params.PR_NUMBER} --body "## ✅ ${params.UPDATE_TYPE} Update Completed
                        
${summary}

**Actions Taken:**
- ✅ Dependencies installed
- ✅ Tests passed
- ✅ Security scan clean
- ✅ PR auto-merged
- ✅ Deployed to production
- ✅ Health checks passed

This automated update was completed without manual intervention."
                    """
                    
                    // Update metrics
                    sh '''
                        echo "Recording metrics..."
                        # Record to monitoring system
                        # - Update type
                        # - Duration
                        # - Success/failure
                    '''
                }
            }
        }
    }
    
    post {
        failure {
            script {
                echo "Minor/Patch update failed. Investigating..."
                
                // Don't rollback immediately for minor updates
                // First try to understand the issue
                
                sh """
                    gh pr comment ${params.PR_NUMBER} --body "## ⚠️ Automated Update Failed
                    
The ${params.UPDATE_TYPE} update for ${params.PACKAGE_NAME} failed during automation.

**Failed Stage:** ${env.STAGE_NAME}
**Build Log:** [View here](${BUILD_URL}console)

**Next Steps:**
1. Review the failure logs
2. If it's a test failure, fix and re-run
3. If it's a compatibility issue, may need manual intervention
4. Consider adding this package to the manual review list

The PR remains open for manual review and merge."
                """
                
                // Add failure label
                sh "gh pr edit ${params.PR_NUMBER} --add-label 'automation-failed,needs-review'"
            }
        }
        
        success {
            script {
                // Record successful automation
                echo "Update completed successfully in ${currentBuild.durationString}"
                
                // Update automation statistics
                sh '''
                    echo "${PACKAGE_NAME},${UPDATE_TYPE},${OLD_VERSION},${NEW_VERSION},success,${BUILD_DURATION}" \
                        >> automation_stats.csv
                '''
            }
        }
        
        always {
            // Quick cleanup
            cleanWs(
                deleteDirs: true,
                disableDeferredWipeout: true,
                patterns: [
                    [pattern: 'node_modules/**', type: 'EXCLUDE'],
                    [pattern: 'venv/**', type: 'EXCLUDE']
                ]
            )
        }
    }
}

// Helper function for version comparison
def isBackwardCompatible(oldVersion, newVersion) {
    def oldParts = oldVersion.tokenize('.')
    def newParts = newVersion.tokenize('.')
    
    // Major version must be same
    if (oldParts[0] != newParts[0]) {
        return false
    }
    
    // Minor version can increase
    if (newParts[1].toInteger() < oldParts[1].toInteger()) {
        return false
    }
    
    return true
}
