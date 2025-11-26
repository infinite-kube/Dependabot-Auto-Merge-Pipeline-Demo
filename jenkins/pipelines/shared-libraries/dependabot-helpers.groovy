// Shared Library for Dependabot Pipeline Helper Functions

package com.company.dependabot

import groovy.json.JsonSlurper
import groovy.json.JsonBuilder

class DependabotHelpers implements Serializable {
    
    def script
    
    DependabotHelpers(script) {
        this.script = script
    }
    
    /**
     * Parse Dependabot PR to extract update information
     */
    def parseDependabotPR(prNumber, repoName) {
        def prData = script.sh(
            script: """
                curl -s https://api.github.com/repos/${repoName}/pulls/${prNumber} \
                    -H "Authorization: token \${GITHUB_TOKEN}"
            """,
            returnStdout: true
        )
        
        def json = new JsonSlurper().parseText(prData)
        def updateInfo = [:]
        
        // Parse title for version information
        def title = json.title
        def versionPattern = ~/from ([\d\.]+) to ([\d\.]+)/
        def matcher = title =~ versionPattern
        
        if (matcher.find()) {
            updateInfo.oldVersion = matcher[0][1]
            updateInfo.newVersion = matcher[0][2]
            updateInfo.updateType = determineUpdateType(matcher[0][1], matcher[0][2])
        }
        
        // Parse package name
        def packagePattern = ~/Bump ([\w\-\/]+)/
        matcher = title =~ packagePattern
        if (matcher.find()) {
            updateInfo.packageName = matcher[0][1]
        }
        
        // Check for security update
        updateInfo.isSecurityUpdate = json.body?.contains('security') || 
                                     json.labels?.any { it.name == 'security' }
        
        // Get ecosystem
        updateInfo.ecosystem = detectEcosystem(json.files_url)
        
        return updateInfo
    }
    
    /**
     * Determine update type based on semantic versioning
     */
    def determineUpdateType(oldVersion, newVersion) {
        def oldParts = oldVersion.tokenize('.').collect { it as Integer }
        def newParts = newVersion.tokenize('.').collect { it as Integer }
        
        if (oldParts[0] != newParts[0]) {
            return 'major'
        } else if (oldParts.size() > 1 && newParts.size() > 1 && oldParts[1] != newParts[1]) {
            return 'minor'
        } else {
            return 'patch'
        }
    }
    
    /**
     * Detect package ecosystem from PR files
     */
    def detectEcosystem(filesUrl) {
        def files = script.sh(
            script: "curl -s ${filesUrl} -H 'Authorization: token \${GITHUB_TOKEN}'",
            returnStdout: true
        )
        
        def filesJson = new JsonSlurper().parseText(files)
        
        for (file in filesJson) {
            switch(file.filename) {
                case ~/.*package\.json$/:
                case ~/.*package-lock\.json$/:
                    return 'npm'
                case ~/.*requirements\.txt$/:
                case ~/.*Pipfile$/:
                case ~/.*setup\.py$/:
                    return 'pip'
                case ~/.*pom\.xml$/:
                    return 'maven'
                case ~/.*build\.gradle$/:
                    return 'gradle'
                case ~/.*go\.mod$/:
                    return 'go'
                case ~/.*Gemfile$/:
                    return 'bundler'
                case ~/.*\.csproj$/:
                    return 'nuget'
                case ~/.*Dockerfile$/:
                    return 'docker'
            }
        }
        
        return 'unknown'
    }
    
    /**
     * Calculate risk score for the update
     */
    def calculateRiskScore(updateInfo) {
        def riskScore = 0
        
        // Base score by update type
        switch(updateInfo.updateType) {
            case 'major':
                riskScore += 70
                break
            case 'minor':
                riskScore += 30
                break
            case 'patch':
                riskScore += 10
                break
        }
        
        // Adjust for security updates (lower risk for patches, higher urgency)
        if (updateInfo.isSecurityUpdate) {
            if (updateInfo.updateType == 'patch') {
                riskScore -= 5  // Security patches are usually safe
            } else {
                riskScore += 10  // Security updates for major/minor need careful review
            }
        }
        
        // Adjust based on package criticality
        def criticalPackages = ['express', 'react', 'angular', 'vue', 'django', 'spring']
        if (criticalPackages.any { updateInfo.packageName?.contains(it) }) {
            riskScore += 20
        }
        
        // Normalize to 0-100
        riskScore = Math.min(100, Math.max(0, riskScore))
        
        return riskScore
    }
    
    /**
     * Determine if auto-merge should be allowed
     */
    def shouldAutoMerge(updateInfo, testResults) {
        // Never auto-merge major updates
        if (updateInfo.updateType == 'major') {
            return false
        }
        
        // Check test results
        if (!testResults.allPassed) {
            return false
        }
        
        // Calculate risk score
        def riskScore = calculateRiskScore(updateInfo)
        
        // Auto-merge if risk score is below threshold
        return riskScore < 40
    }
    
    /**
     * Run security audit for the ecosystem
     */
    def runSecurityAudit(ecosystem, workDir) {
        def auditResult = [passed: true, vulnerabilities: []]
        
        script.dir(workDir) {
            switch(ecosystem) {
                case 'npm':
                    def npmAudit = script.sh(
                        script: 'npm audit --json',
                        returnStdout: true,
                        returnStatus: true
                    )
                    if (npmAudit.status != 0) {
                        def auditJson = new JsonSlurper().parseText(npmAudit.stdout)
                        auditResult.passed = false
                        auditResult.vulnerabilities = auditJson.vulnerabilities
                    }
                    break
                    
                case 'pip':
                    def safetyCheck = script.sh(
                        script: 'safety check --json',
                        returnStdout: true,
                        returnStatus: true
                    )
                    if (safetyCheck.status != 0) {
                        auditResult.passed = false
                        auditResult.vulnerabilities = new JsonSlurper().parseText(safetyCheck.stdout)
                    }
                    break
                    
                case 'maven':
                    def owaspCheck = script.sh(
                        script: 'mvn dependency-check:check',
                        returnStatus: true
                    )
                    auditResult.passed = (owaspCheck == 0)
                    break
            }
        }
        
        return auditResult
    }
    
    /**
     * Generate deployment strategy based on update type and risk
     */
    def getDeploymentStrategy(updateInfo) {
        def riskScore = calculateRiskScore(updateInfo)
        
        if (riskScore > 70) {
            return [
                strategy: 'blue-green',
                stages: ['staging', 'canary', 'production'],
                rollbackEnabled: true,
                approvalRequired: true
            ]
        } else if (riskScore > 40) {
            return [
                strategy: 'canary',
                stages: ['staging', 'production'],
                canaryPercentage: 10,
                rollbackEnabled: true,
                approvalRequired: false
            ]
        } else {
            return [
                strategy: 'rolling',
                stages: ['production'],
                rollbackEnabled: true,
                approvalRequired: false
            ]
        }
    }
    
    /**
     * Post status update to GitHub PR
     */
    def updatePRStatus(prNumber, repoName, status, message) {
        def statusColor = status == 'success' ? 'good' : status == 'failure' ? 'danger' : 'warning'
        def statusEmoji = status == 'success' ? '✅' : status == 'failure' ? '❌' : '⚠️'
        
        def comment = """
        ## ${statusEmoji} Pipeline Status Update
        
        **Status**: ${status}
        **Message**: ${message}
        **Build**: [View Details](${script.env.BUILD_URL})
        **Time**: ${new Date().format('yyyy-MM-dd HH:mm:ss')}
        """
        
        script.sh """
            curl -X POST https://api.github.com/repos/${repoName}/issues/${prNumber}/comments \
                -H "Authorization: token \${GITHUB_TOKEN}" \
                -H "Content-Type: application/json" \
                -d '{"body": ${JsonBuilder(comment).toString()}}'
        """
    }
    
    /**
     * Batch process multiple Dependabot PRs
     */
    def batchProcessPRs(repoName, maxPRs = 10) {
        def prs = script.sh(
            script: """
                curl -s "https://api.github.com/repos/${repoName}/pulls?state=open&creator=dependabot[bot]&per_page=${maxPRs}" \
                    -H "Authorization: token \${GITHUB_TOKEN}"
            """,
            returnStdout: true
        )
        
        def prList = new JsonSlurper().parseText(prs)
        def processedPRs = []
        
        prList.each { pr ->
            def updateInfo = parseDependabotPR(pr.number, repoName)
            
            // Group by update type
            if (updateInfo.updateType == 'patch' && !updateInfo.isSecurityUpdate) {
                processedPRs << [
                    number: pr.number,
                    title: pr.title,
                    updateInfo: updateInfo,
                    priority: 'low'
                ]
            }
        }
        
        return processedPRs
    }
    
    /**
     * Check if deployment should proceed based on health metrics
     */
    def checkDeploymentHealth(appUrl, thresholds = [:]) {
        def defaultThresholds = [
            maxErrorRate: 1.0,  // 1% error rate
            maxResponseTime: 1000,  // 1 second
            minSuccessRate: 99.0  // 99% success rate
        ]
        
        thresholds = defaultThresholds + thresholds
        
        // Simulate health check (replace with actual monitoring API calls)
        def healthCheck = script.sh(
            script: "curl -s ${appUrl}/health",
            returnStdout: true,
            returnStatus: true
        )
        
        if (healthCheck.status != 0) {
            return [healthy: false, reason: 'Health endpoint not responding']
        }
        
        // Parse health metrics
        try {
            def health = new JsonSlurper().parseText(healthCheck.stdout)
            
            if (health.errorRate > thresholds.maxErrorRate) {
                return [healthy: false, reason: "Error rate ${health.errorRate}% exceeds threshold ${thresholds.maxErrorRate}%"]
            }
            
            if (health.responseTime > thresholds.maxResponseTime) {
                return [healthy: false, reason: "Response time ${health.responseTime}ms exceeds threshold ${thresholds.maxResponseTime}ms"]
            }
            
            return [healthy: true, metrics: health]
        } catch (Exception e) {
            return [healthy: false, reason: "Failed to parse health metrics: ${e.message}"]
        }
    }
    
    /**
     * Generate changelog entry for the update
     */
    def generateChangelogEntry(updateInfo) {
        def date = new Date().format('yyyy-MM-dd')
        def entry = """
### [${date}] Dependency Update

**Package**: ${updateInfo.packageName}
**Version**: ${updateInfo.oldVersion} → ${updateInfo.newVersion}
**Type**: ${updateInfo.updateType}
${updateInfo.isSecurityUpdate ? '**Security**: This is a security update' : ''}

#### Changes
- Updated ${updateInfo.packageName} from ${updateInfo.oldVersion} to ${updateInfo.newVersion}
${updateInfo.isSecurityUpdate ? '- Addresses security vulnerabilities' : ''}
- Automated update via Dependabot

---
"""
        return entry
    }
    
    /**
     * Rollback deployment if health checks fail
     */
    def rollbackDeployment(deployment, reason) {
        script.echo "Initiating rollback due to: ${reason}"
        
        switch(deployment.type) {
            case 'kubernetes':
                script.sh "kubectl rollout undo deployment/${deployment.name} -n ${deployment.namespace}"
                script.sh "kubectl rollout status deployment/${deployment.name} -n ${deployment.namespace}"
                break
                
            case 'docker':
                script.sh "docker service rollback ${deployment.service}"
                break
                
            case 'helm':
                script.sh "helm rollback ${deployment.release} 0"
                break
        }
        
        return true
    }
}

// Export for use in pipelines
return new DependabotHelpers(this)
