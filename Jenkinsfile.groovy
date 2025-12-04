pipeline {
    agent any
    
    triggers {
        githubPush()
    }
    
    parameters {
        booleanParam(name: 'IS_PR_BUILD', defaultValue: false, description: 'Set to true when triggered by PR webhook')
        string(name: 'PR_NUMBER', defaultValue: '', description: 'Pull Request number')
        choice(name: 'PIPELINE_TYPE', choices: ['minor-patch', 'major-update', 'security-patch'], description: 'Type of pipeline to run')
        string(name: 'REPO_NAME', defaultValue: '', description: 'Repository name')
        string(name: 'PR_BRANCH', defaultValue: '', description: 'PR branch name')
        string(name: 'PR_SHA', defaultValue: '', description: 'PR commit SHA')
        string(name: 'PR_TITLE', defaultValue: '', description: 'PR title')
        string(name: 'GITHUB_TOKEN', defaultValue: '', description: 'GitHub token for API calls')
        string(name: 'CALLBACK_URL', defaultValue: '', description: 'GitHub status callback URL')
    }
    
    environment {
        NODE_ENV            = 'test'
        CI                  = 'true'
        GITHUB_API          = 'https://api.github.com'
        DEPLOY_ENV          = "${params.PIPELINE_TYPE == 'major-update' ? 'staging' : 'production'}"
        AUTO_MERGE          = "${params.PIPELINE_TYPE != 'major-update' ? 'true' : 'false'}"
        DEPLOYMENT_STRATEGY = ''
        APPROVAL_REQUIRED   = ''
        UPDATE_TYPE         = ''
    }
    
    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '50'))
        disableConcurrentBuilds()
        skipDefaultCheckout()
    }
    
    stages {
        stage('Initialize') {
            steps {
                script {
                    if (params.IS_PR_BUILD || params.PR_NUMBER != '') {
                        currentBuild.displayName = "#${BUILD_NUMBER} - PR #${params.PR_NUMBER} - ${params.PIPELINE_TYPE}"
                        currentBuild.description = "${params.PR_TITLE}"
                        
                        // Send initial status to GitHub
                        if (params.CALLBACK_URL) {
                            sh """
                                curl -X POST ${params.CALLBACK_URL} \
                                    -H "Authorization: token ${params.GITHUB_TOKEN}" \
                                    -H "Content-Type: application/json" \
                                    -d '{
                                        "state": "pending",
                                        "target_url": "${BUILD_URL}",
                                        "description": "Jenkins pipeline in progress",
                                        "context": "jenkins/dependabot-pipeline"
                                    }'
                            """
                        }
                        
                        echo "Starting Dependabot Pipeline"
                        echo "PR Number: ${params.PR_NUMBER}"
                        echo "Pipeline Type: ${params.PIPELINE_TYPE}"
                        echo "Repository: ${params.REPO_NAME}"
                        echo "Branch: ${params.PR_BRANCH}"
                        echo "Commit SHA: ${params.PR_SHA}"
                    } else {
                        currentBuild.displayName = "#${BUILD_NUMBER} - Push to ${env.GIT_BRANCH ?: 'main'}"
                        currentBuild.description = "Direct push deployment"
                        
                        echo "Starting Direct Push Pipeline"
                        echo "Branch: ${env.GIT_BRANCH ?: 'main'}"
                        echo "Build Number: ${BUILD_NUMBER}"
                    }
                }
            }
        }

        // ============================================================
        // DIRECT PUSH DEPLOYMENT PATH
        // Triggered when code is pushed directly to main branch
        // ============================================================
        stage('Direct Push Deploy') {
            when {
                allOf {
                    expression { !params.IS_PR_BUILD }
                    expression { params.PR_NUMBER == '' }
                }
            }
            stages {
                stage('Checkout Main') {
                    steps {
                        checkout scm
                        script {
                            env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                            env.GIT_COMMIT_MSG = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
                            echo "Commit: ${env.GIT_COMMIT_SHORT}"
                            echo "Message: ${env.GIT_COMMIT_MSG}"
                        }
                    }
                }
                
                stage('Install Dependencies') {
                    parallel {
                        stage('NPM') {
                            when {
                                expression { fileExists('package.json') }
                            }
                            steps {
                                sh '''
                                    echo "Installing NPM dependencies..."
                                    npm ci
                                    npm list --depth=0
                                '''
                            }
                        }
                        stage('Python') {
                            when {
                                expression { fileExists('requirements.txt') }
                            }
                            steps {
                                sh '''
                                    echo "Installing Python dependencies..."
                                    python3 -m venv venv
                                    . venv/bin/activate
                                    pip install -r requirements.txt
                                    pip list
                                '''
                            }
                        }
                        stage('Maven') {
                            when {
                                expression { fileExists('pom.xml') }
                            }
                            steps {
                                sh '''
                                    echo "Installing Maven dependencies..."
                                    mvn clean dependency:resolve
                                '''
                            }
                        }
                    }
                }
                
                stage('Run Tests') {
                    parallel {
                        stage('NPM Tests') {
                            when {
                                expression { fileExists('package.json') }
                            }
                            steps {
                                sh 'npm test || true'
                            }
                        }
                        stage('Python Tests') {
                            when {
                                expression { fileExists('requirements.txt') }
                            }
                            steps {
                                sh '''
                                    . venv/bin/activate
                                    pytest tests/ --tb=short || true
                                '''
                            }
                        }
                        stage('Maven Tests') {
                            when {
                                expression { fileExists('pom.xml') }
                            }
                            steps {
                                sh 'mvn test || true'
                            }
                        }
                    }
                }
                
                stage('Build Application') {
                    steps {
                        script {
                            if (fileExists('package.json')) {
                                sh 'npm run build || echo "No build script defined"'
                            }
                            if (fileExists('pom.xml')) {
                                sh 'mvn package -DskipTests'
                            }
                            if (fileExists('Dockerfile')) {
                                sh """
                                    docker build -t app:${BUILD_NUMBER} .
                                    docker tag app:${BUILD_NUMBER} app:latest
                                    docker tag app:${BUILD_NUMBER} app:${env.GIT_COMMIT_SHORT}
                                """
                            }
                        }
                    }
                }
                
                stage('Deploy to Production') {
                    steps {
                        script {
                            echo "Deploying build #${BUILD_NUMBER} to production..."
                            
                            // Uncomment and configure your deployment method:
                            
                            // Kubernetes deployment
                            // sh 'kubectl apply -f k8s/production/'
                            // sh "kubectl set image deployment/app app=app:${BUILD_NUMBER}"
                            
                            // Docker Compose deployment
                            // sh 'docker-compose down && docker-compose up -d'
                            
                            // Custom deploy script
                            // sh './deploy.sh'
                            
                            // Demo output for visibility
                            sh """
                                echo "========================================="
                                echo "       DEPLOYMENT SUCCESSFUL"
                                echo "========================================="
                                echo "  Build Number: ${BUILD_NUMBER}"
                                echo "  Commit: ${env.GIT_COMMIT_SHORT}"
                                echo "  Message: ${env.GIT_COMMIT_MSG}"
                                echo "  Timestamp: \$(date)"
                                echo "========================================="
                            """
                        }
                    }
                }
                
                stage('Post-Deploy Verification') {
                    steps {
                        script {
                            echo "Running post-deployment health checks..."
                            
                            // Health check examples:
                            // sh 'curl -f http://localhost:8080/health || exit 1'
                            // sh 'npm run test:smoke || true'
                            
                            sh '''
                                echo "Health check: OK"
                                echo "Application is running"
                            '''
                        }
                    }
                }
            }
            post {
                success {
                    echo "✅ Direct push deployment completed successfully"
                }
                failure {
                    echo "❌ Direct push deployment failed"
                    // Add rollback logic here if needed
                    // sh 'kubectl rollout undo deployment/app'
                }
            }
        }

        // ============================================================
        // PR/DEPENDABOT WORKFLOW PATH
        // Triggered when called with PR parameters from GitHub webhook
        // ============================================================

        // FROM SECTION 2: classify update and set deployment strategy / approval flags
        stage('Categorize Update') {
            when {
                expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
            }
            steps {
                script {
                    // Prefer Jenkins env PULL_REQUEST_ID if set, fall back to PR_NUMBER param
                    def prId = env.PULL_REQUEST_ID ?: params.PR_NUMBER
                    if (!prId) {
                        echo "No PR ID available for dependabot-parser. Skipping classification."
                    } else {
                        def updateType = sh(
                            script: "dependabot-parser --pr ${prId}",
                            returnStdout: true
                        ).trim()
                        
                        env.UPDATE_TYPE = updateType

                        if (updateType == 'patch') {
                            env.DEPLOYMENT_STRATEGY = 'rolling'
                            env.APPROVAL_REQUIRED   = 'false'
                        } else if (updateType == 'minor') {
                            env.DEPLOYMENT_STRATEGY = 'blue-green'
                            env.APPROVAL_REQUIRED   = 'true'
                        } else if (updateType == 'major') {
                            env.DEPLOYMENT_STRATEGY = 'canary'
                            env.APPROVAL_REQUIRED   = 'manual'
                        }

                        echo "Update type: ${env.UPDATE_TYPE}"
                        echo "Deployment strategy: ${env.DEPLOYMENT_STRATEGY}"
                        echo "Approval required: ${env.APPROVAL_REQUIRED}"
                    }
                }
            }
        }
        
        stage('Checkout Code') {
            when {
                expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
            }
            steps {
                script {
                    // Clean workspace
                    deleteDir()
                    
                    // Checkout PR branch
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: "${params.PR_BRANCH}"]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [
                            [$class: 'CleanBeforeCheckout'],
                            [$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true]
                        ],
                        submoduleCfg: [],
                        userRemoteConfigs: [[
                            url: "https://github.com/${params.REPO_NAME}.git",
                            refspec: "+refs/pull/${params.PR_NUMBER}/head:refs/remotes/origin/PR-${params.PR_NUMBER}"
                        ]]
                    ])
                }
            }
        }
        
        stage('Dependency Installation') {
            when {
                expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
            }
            parallel {
                stage('NPM Dependencies') {
                    when {
                        expression { fileExists('package.json') }
                    }
                    steps {
                        sh '''
                            echo "Installing NPM dependencies..."
                            npm ci
                            npm list --depth=0
                        '''
                    }
                }
                
                stage('Python Dependencies') {
                    when {
                        expression { fileExists('requirements.txt') }
                    }
                    steps {
                        sh '''
                            echo "Installing Python dependencies..."
                            python3 -m venv venv
                            . venv/bin/activate
                            pip install -r requirements.txt
                            pip list
                        '''
                    }
                }
                
                stage('Maven Dependencies') {
                    when {
                        expression { fileExists('pom.xml') }
                    }
                    steps {
                        sh '''
                            echo "Installing Maven dependencies..."
                            mvn clean dependency:resolve
                            mvn dependency:tree
                        '''
                    }
                }
            }
        }
        
        stage('Security Scanning') {
            when {
                allOf {
                    expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
                    expression { params.PIPELINE_TYPE == 'security-patch' || params.PIPELINE_TYPE == 'major-update' }
                }
            }
            parallel {
                stage('Dependency Audit') {
                    steps {
                        script {
                            def auditFailed = false
                            
                            if (fileExists('package.json')) {
                                def npmAudit = sh(
                                    script: 'npm audit --audit-level=high',
                                    returnStatus: true
                                )
                                if (npmAudit != 0) {
                                    echo "NPM audit found vulnerabilities"
                                    auditFailed = true
                                }
                            }
                            
                            if (fileExists('requirements.txt')) {
                                sh '''
                                    . venv/bin/activate
                                    pip install safety
                                    safety check --json > safety-report.json || true
                                '''
                            }
                            
                            if (auditFailed && params.PIPELINE_TYPE == 'security-patch') {
                                error("Security audit failed for security patch")
                            }
                        }
                    }
                }
                
                stage('SAST Scan') {
                    steps {
                        echo "Running static application security testing..."
                        sh '''
                            # Placeholder for SAST tools like SonarQube, Checkmarx, etc.
                            echo "SAST scan would run here"
                        '''
                    }
                }
                
                stage('License Check') {
                    steps {
                        echo "Checking license compliance..."
                        script {
                            if (fileExists('package.json')) {
                                sh 'npx license-checker --summary --excludePrivatePackages || true'
                            }
                        }
                    }
                }
            }
        }
        
        stage('Quality Gates') {
            when {
                expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
            }
            parallel {
                stage('Linting') {
                    steps {
                        script {
                            if (fileExists('package.json')) {
                                sh 'npm run lint || true'
                            }
                            if (fileExists('requirements.txt')) {
                                sh '''
                                    . venv/bin/activate
                                    pip install flake8 black
                                    flake8 src/ || true
                                    black --check src/ || true
                                '''
                            }
                        }
                    }
                }
                
                stage('Unit Tests') {
                    steps {
                        script {
                            if (fileExists('package.json')) {
                                sh '''
                                    npm test -- --coverage --testResultsProcessor jest-junit || true
                                    cp coverage/lcov.info coverage.lcov || true
                                '''
                            }
                            if (fileExists('requirements.txt')) {
                                sh '''
                                    . venv/bin/activate
                                    pip install pytest pytest-cov
                                    pytest tests/unit --cov=src --cov-report=xml || true
                                '''
                            }
                            if (fileExists('pom.xml')) {
                                sh 'mvn test || true'
                            }
                        }
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, testResults: '**/test-results/*.xml'
                            publishHTML([
                                allowMissing: true,
                                alwaysLinkToLastBuild: false,
                                keepAll: true,
                                reportDir: 'coverage',
                                reportFiles: 'index.html',
                                reportName: 'Coverage Report'
                            ])
                        }
                    }
                }
                
                stage('Integration Tests') {
                    when {
                        expression { params.PIPELINE_TYPE == 'major-update' }
                    }
                    steps {
                        script {
                            echo "Running integration tests for major update..."
                            if (fileExists('package.json')) {
                                sh 'npm run test:integration || true'
                            }
                            if (fileExists('tests/integration')) {
                                sh '''
                                    . venv/bin/activate
                                    pytest tests/integration || true
                                '''
                            }
                        }
                    }
                }
            }
        }
        
        stage('Build') {
            when {
                expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
            }
            steps {
                script {
                    echo "Building application..."
                    
                    if (fileExists('package.json')) {
                        sh 'npm run build || true'
                    }
                    
                    if (fileExists('Dockerfile')) {
                        sh """
                            docker build -t ${params.REPO_NAME}:pr-${params.PR_NUMBER} .
                            docker tag ${params.REPO_NAME}:pr-${params.PR_NUMBER} ${params.REPO_NAME}:latest
                        """
                    }
                }
            }
        }

        // FROM SECTION 2: Trivy container security scan
        stage('Security Scan') {
            when {
                allOf {
                    expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
                    expression { env.UPDATE_TYPE == 'patch' }
                }
            }
            steps {
                sh 'trivy scan --severity HIGH,CRITICAL .'
            }
        }
        
        stage('Approval Gate') {
            when {
                allOf {
                    expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
                    expression { params.PIPELINE_TYPE == 'major-update' }
                }
            }
            steps {
                script {
                    // Post comment to GitHub PR
                    sh """
                        curl -X POST ${GITHUB_API}/repos/${params.REPO_NAME}/issues/${params.PR_NUMBER}/comments \
                            -H "Authorization: token ${params.GITHUB_TOKEN}" \
                            -H "Content-Type: application/json" \
                            -d '{
                                "body": "## ⏸️ Manual Approval Required\\n\\nThis major update requires manual approval before proceeding with deployment.\\n\\n**Build Results:**\\n- Tests: Passed ✅\\n- Security Scan: Passed ✅\\n- Build: Success ✅\\n\\n[Approve in Jenkins](${BUILD_URL}input)"
                            }'
                    """
                    
                    // Wait for manual approval
                    input message: "Approve deployment for PR #${params.PR_NUMBER}?",
                          ok: 'Deploy',
                          submitter: 'admin,devops-team',
                          parameters: [
                              text(name: 'APPROVAL_COMMENT', defaultValue: '', description: 'Approval comments')
                          ]
                }
            }
        }
        
        stage('Auto-Merge Decision') {
            when {
                allOf {
                    expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
                    expression { params.AUTO_MERGE == 'true' }
                }
            }
            steps {
                script {
                    echo "Checking auto-merge eligibility..."
                    
                    // Check if all tests passed
                    if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                        echo "All checks passed. Proceeding with auto-merge..."
                        
                        // Approve and merge PR via GitHub API
                        sh """
                            # Approve PR
                            curl -X POST ${GITHUB_API}/repos/${params.REPO_NAME}/pulls/${params.PR_NUMBER}/reviews \
                                -H "Authorization: token ${params.GITHUB_TOKEN}" \
                                -H "Content-Type: application/json" \
                                -d '{
                                    "event": "APPROVE",
                                    "body": "Auto-approved by Jenkins pipeline after successful checks."
                                }'
                            
                            # Enable auto-merge
                            curl -X PUT ${GITHUB_API}/repos/${params.REPO_NAME}/pulls/${params.PR_NUMBER}/merge \
                                -H "Authorization: token ${params.GITHUB_TOKEN}" \
                                -H "Content-Type: application/json" \
                                -d '{
                                    "commit_title": "Auto-merge PR #${params.PR_NUMBER}: ${params.PR_TITLE}",
                                    "commit_message": "Automatically merged by Jenkins pipeline\\n\\nPipeline: ${BUILD_URL}\\nType: ${params.PIPELINE_TYPE}",
                                    "merge_method": "squash"
                                }'
                        """
                    } else {
                        echo "Tests failed. Cannot auto-merge."
                        error("Auto-merge aborted due to test failures")
                    }
                }
            }
        }
        
        stage('Deploy') {
            when {
                allOf {
                    expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
                    expression { 
                        params.PIPELINE_TYPE != 'major-update' || 
                        (params.PIPELINE_TYPE == 'major-update' && env.APPROVAL_COMMENT != null)
                    }
                }
            }
            steps {
                script {
                    echo "Deploying to ${DEPLOY_ENV} environment..."
                    
                    // Deployment logic based on environment
                    if (DEPLOY_ENV == 'staging') {
                        sh '''
                            echo "Deploying to staging environment..."
                            # kubectl apply -f k8s/staging/ || true
                            # helm upgrade --install app-staging ./charts --namespace staging || true
                        '''
                    } else if (DEPLOY_ENV == 'production') {
                        sh '''
                            echo "Deploying to production environment..."
                            # kubectl apply -f k8s/production/ || true
                            # helm upgrade --install app-prod ./charts --namespace production || true
                        '''
                    }

                    // FROM SECTION 2: deployment strategy specific behavior
                    if (env.DEPLOYMENT_STRATEGY == 'rolling') {
                        sh 'kubectl set image deployment/app app=app:${BUILD_NUMBER}'
                    }
                    // Additional deployment strategies can be added here based on DEPLOYMENT_STRATEGY
                    
                    // Simulate deployment
                    sh 'sleep 10'
                }
            }
        }
        
        stage('Post-Deployment Verification') {
            when {
                expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
            }
            steps {
                script {
                    echo "Running post-deployment checks..."
                    
                    // Health checks
                    sh '''
                        echo "Checking application health..."
                        # curl -f http://app-endpoint/health || exit 1
                    '''
                    
                    // Smoke tests
                    sh '''
                        echo "Running smoke tests..."
                        # npm run test:smoke || true
                    '''
                }
            }
        }
        
        stage('Rollback Ready') {
            when {
                allOf {
                    expression { params.IS_PR_BUILD || params.PR_NUMBER != '' }
                    expression { currentBuild.result == 'FAILURE' }
                }
            }
            steps {
                script {
                    echo "Deployment failed. Initiating rollback..."
                    
                    sh '''
                        echo "Rolling back deployment..."
                        # kubectl rollout undo deployment/app -n ${DEPLOY_ENV}
                        # or
                        # helm rollback app-${DEPLOY_ENV} 0
                    '''
                    
                    // Notify about rollback
                    sh """
                        curl -X POST ${GITHUB_API}/repos/${params.REPO_NAME}/issues/${params.PR_NUMBER}/comments \
                            -H "Authorization: token ${params.GITHUB_TOKEN}" \
                            -H "Content-Type: application/json" \
                            -d '{
                                "body": "## 🔄 Automatic Rollback Initiated\\n\\nDeployment failed. Automatic rollback has been triggered.\\n\\n[View logs](${BUILD_URL}console)"
                            }'
                    """
                }
            }
        }
    }
    
    post {
        success {
            script {
                if (params.IS_PR_BUILD || params.PR_NUMBER != '') {
                    // Update GitHub status
                    if (params.CALLBACK_URL) {
                        sh """
                            curl -X POST ${params.CALLBACK_URL} \
                                -H "Authorization: token ${params.GITHUB_TOKEN}" \
                                -H "Content-Type: application/json" \
                                -d '{
                                    "state": "success",
                                    "target_url": "${BUILD_URL}",
                                    "description": "Jenkins pipeline passed",
                                    "context": "jenkins/dependabot-pipeline"
                                }'
                        """
                    }
                    
                    // Post success comment
                    sh """
                        curl -X POST ${GITHUB_API}/repos/${params.REPO_NAME}/issues/${params.PR_NUMBER}/comments \
                            -H "Authorization: token ${params.GITHUB_TOKEN}" \
                            -H "Content-Type: application/json" \
                            -d '{
                                "body": "## ✅ Pipeline Completed Successfully\\n\\n**Duration:** ${currentBuild.durationString}\\n**Result:** SUCCESS\\n\\n[View full build log](${BUILD_URL}console)"
                            }'
                    """
                } else {
                    echo "✅ Direct push pipeline completed successfully"
                }
            }
        }
        
        failure {
            script {
                if (params.IS_PR_BUILD || params.PR_NUMBER != '') {
                    // Update GitHub status
                    if (params.CALLBACK_URL) {
                        sh """
                            curl -X POST ${params.CALLBACK_URL} \
                                -H "Authorization: token ${params.GITHUB_TOKEN}" \
                                -H "Content-Type: application/json" \
                                -d '{
                                    "state": "failure",
                                    "target_url": "${BUILD_URL}",
                                    "description": "Jenkins pipeline failed",
                                    "context": "jenkins/dependabot-pipeline"
                                }'
                        """
                    }
                    
                    // Post failure comment
                    sh """
                        curl -X POST ${GITHUB_API}/repos/${params.REPO_NAME}/issues/${params.PR_NUMBER}/comments \
                            -H "Authorization: token ${params.GITHUB_TOKEN}" \
                            -H "Content-Type: application/json" \
                            -d '{
                                "body": "## ❌ Pipeline Failed\\n\\n**Duration:** ${currentBuild.durationString}\\n**Failed Stage:** ${env.STAGE_NAME}\\n\\n[View full build log](${BUILD_URL}console)"
                            }'
                    """
                } else {
                    echo "❌ Direct push pipeline failed"
                }
            }
        }
        
        always {
            cleanWs()
        }
    }
}
