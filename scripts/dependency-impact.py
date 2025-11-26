#!/usr/bin/env python3

"""
Dependency Impact Analysis Script
Analyzes the impact of dependency updates on the codebase
"""

import json
import os
import re
import sys
import subprocess
import argparse
from pathlib import Path
from typing import Dict, List, Set, Tuple
from collections import defaultdict
import ast

class DependencyImpactAnalyzer:
    def __init__(self, repo_path: str = "."):
        self.repo_path = Path(repo_path)
        self.file_extensions = {
            'javascript': ['.js', '.jsx', '.ts', '.tsx'],
            'python': ['.py'],
            'java': ['.java'],
            'go': ['.go'],
            'ruby': ['.rb'],
            'rust': ['.rs']
        }
        self.import_patterns = {
            'javascript': [
                r'import\s+.*from\s+[\'"]([^\'""]+)[\'"]',
                r'require\s*\(\s*[\'"]([^\'""]+)[\'"]\s*\)',
                r'import\s*\(\s*[\'"]([^\'""]+)[\'"]\s*\)'
            ],
            'python': [
                r'import\s+(\S+)',
                r'from\s+(\S+)\s+import'
            ],
            'java': [
                r'import\s+([\w\.]+);'
            ]
        }

    def detect_ecosystem(self) -> str:
        """Detect the primary ecosystem of the repository"""
        if (self.repo_path / 'package.json').exists():
            return 'npm'
        elif (self.repo_path / 'requirements.txt').exists() or (self.repo_path / 'setup.py').exists():
            return 'python'
        elif (self.repo_path / 'pom.xml').exists():
            return 'maven'
        elif (self.repo_path / 'go.mod').exists():
            return 'go'
        elif (self.repo_path / 'Gemfile').exists():
            return 'ruby'
        elif (self.repo_path / 'Cargo.toml').exists():
            return 'cargo'
        return 'unknown'

    def find_package_usage(self, package_name: str, ecosystem: str) -> Dict[str, List[str]]:
        """Find all files that use a specific package"""
        usage_map = defaultdict(list)
        
        # Determine file extensions to search
        extensions = []
        if ecosystem in ['npm', 'javascript']:
            extensions = self.file_extensions['javascript']
        elif ecosystem == 'python':
            extensions = self.file_extensions['python']
        elif ecosystem == 'maven':
            extensions = self.file_extensions['java']
        
        # Search for imports
        for ext in extensions:
            for file_path in self.repo_path.rglob(f'*{ext}'):
                if 'node_modules' in str(file_path) or 'venv' in str(file_path):
                    continue
                
                try:
                    with open(file_path, 'r', encoding='utf-8') as f:
                        content = f.read()
                        
                        # Check for package usage
                        if self._file_uses_package(content, package_name, ecosystem):
                            relative_path = str(file_path.relative_to(self.repo_path))
                            lines = self._find_usage_lines(content, package_name, ecosystem)
                            usage_map[relative_path] = lines
                except (IOError, UnicodeDecodeError):
                    continue
        
        return dict(usage_map)

    def _file_uses_package(self, content: str, package_name: str, ecosystem: str) -> bool:
        """Check if a file uses a specific package"""
        if ecosystem in ['npm', 'javascript']:
            patterns = self.import_patterns['javascript']
            for pattern in patterns:
                matches = re.findall(pattern, content)
                for match in matches:
                    if package_name in match:
                        return True
        
        elif ecosystem == 'python':
            # Use AST for more accurate Python parsing
            try:
                tree = ast.parse(content)
                for node in ast.walk(tree):
                    if isinstance(node, ast.Import):
                        for alias in node.names:
                            if package_name in alias.name:
                                return True
                    elif isinstance(node, ast.ImportFrom):
                        if node.module and package_name in node.module:
                            return True
            except:
                # Fallback to regex if AST parsing fails
                patterns = self.import_patterns['python']
                for pattern in patterns:
                    if re.search(pattern.replace('(\\S+)', package_name), content):
                        return True
        
        return False

    def _find_usage_lines(self, content: str, package_name: str, ecosystem: str) -> List[str]:
        """Find specific lines where package is used"""
        lines = []
        content_lines = content.split('\n')
        
        for i, line in enumerate(content_lines, 1):
            if package_name in line and ('import' in line or 'require' in line):
                lines.append(f"Line {i}: {line.strip()}")
        
        return lines

    def analyze_dependency_tree(self, package_name: str, ecosystem: str) -> Dict:
        """Analyze the dependency tree for a package"""
        tree_info = {
            'direct_dependents': [],
            'indirect_dependents': [],
            'total_affected': 0
        }
        
        if ecosystem == 'npm':
            try:
                # Use npm to get dependency tree
                result = subprocess.run(
                    ['npm', 'ls', package_name, '--json'],
                    capture_output=True,
                    text=True,
                    cwd=self.repo_path
                )
                if result.returncode == 0:
                    deps = json.loads(result.stdout)
                    tree_info['direct_dependents'] = self._parse_npm_deps(deps, package_name)
            except (subprocess.SubprocessError, json.JSONDecodeError):
                pass
        
        elif ecosystem == 'python':
            try:
                # Use pip to show package dependencies
                result = subprocess.run(
                    ['pip', 'show', package_name],
                    capture_output=True,
                    text=True
                )
                if result.returncode == 0:
                    # Parse pip show output
                    for line in result.stdout.split('\n'):
                        if line.startswith('Required-by:'):
                            deps = line.split(':')[1].strip().split(', ')
                            tree_info['direct_dependents'] = [d for d in deps if d]
            except subprocess.SubprocessError:
                pass
        
        tree_info['total_affected'] = len(tree_info['direct_dependents']) + len(tree_info['indirect_dependents'])
        return tree_info

    def _parse_npm_deps(self, deps_json: Dict, package_name: str) -> List[str]:
        """Parse npm dependency tree"""
        dependents = []
        
        def traverse(obj, parent=''):
            if isinstance(obj, dict):
                if 'dependencies' in obj:
                    for dep, info in obj['dependencies'].items():
                        if package_name in str(info):
                            if parent:
                                dependents.append(parent)
                        traverse(info, dep)
        
        traverse(deps_json)
        return list(set(dependents))

    def calculate_impact_score(self, package_name: str, old_version: str, new_version: str) -> int:
        """Calculate an impact score for the update"""
        score = 0
        
        # Version change magnitude
        old_parts = old_version.split('.')
        new_parts = new_version.split('.')
        
        if len(old_parts) >= 1 and len(new_parts) >= 1:
            if old_parts[0] != new_parts[0]:  # Major version change
                score += 50
            elif len(old_parts) >= 2 and len(new_parts) >= 2:
                if old_parts[1] != new_parts[1]:  # Minor version change
                    score += 20
                else:  # Patch version change
                    score += 5
        
        # Number of affected files
        ecosystem = self.detect_ecosystem()
        usage = self.find_package_usage(package_name, ecosystem)
        affected_files = len(usage)
        
        if affected_files > 50:
            score += 40
        elif affected_files > 20:
            score += 25
        elif affected_files > 10:
            score += 15
        elif affected_files > 0:
            score += 5
        
        # Dependency tree impact
        tree_info = self.analyze_dependency_tree(package_name, ecosystem)
        if tree_info['total_affected'] > 10:
            score += 20
        elif tree_info['total_affected'] > 5:
            score += 10
        elif tree_info['total_affected'] > 0:
            score += 5
        
        return min(score, 100)  # Cap at 100

    def check_breaking_changes(self, package_name: str, old_version: str, new_version: str) -> List[str]:
        """Check for potential breaking changes"""
        breaking_changes = []
        
        # Major version bump usually indicates breaking changes
        old_major = old_version.split('.')[0] if '.' in old_version else old_version
        new_major = new_version.split('.')[0] if '.' in new_version else new_version
        
        if old_major != new_major:
            breaking_changes.append(f"Major version change ({old_major} → {new_major}) - likely breaking changes")
        
        # Check changelog or release notes (simplified)
        changelog_files = ['CHANGELOG.md', 'CHANGELOG', 'HISTORY.md', 'NEWS.md']
        for changelog in changelog_files:
            changelog_path = self.repo_path / changelog
            if changelog_path.exists():
                try:
                    with open(changelog_path, 'r', encoding='utf-8') as f:
                        content = f.read().lower()
                        if 'breaking' in content and new_version.lower() in content:
                            breaking_changes.append(f"Breaking changes mentioned in {changelog}")
                except (IOError, UnicodeDecodeError):
                    pass
        
        return breaking_changes

    def generate_impact_report(self, package_name: str, old_version: str, new_version: str) -> Dict:
        """Generate comprehensive impact report"""
        ecosystem = self.detect_ecosystem()
        usage = self.find_package_usage(package_name, ecosystem)
        tree_info = self.analyze_dependency_tree(package_name, ecosystem)
        impact_score = self.calculate_impact_score(package_name, old_version, new_version)
        breaking_changes = self.check_breaking_changes(package_name, old_version, new_version)
        
        # Categorize affected files
        affected_by_type = defaultdict(list)
        for file_path in usage.keys():
            if 'test' in file_path.lower():
                affected_by_type['tests'].append(file_path)
            elif 'src/' in file_path or 'lib/' in file_path:
                affected_by_type['source'].append(file_path)
            elif 'config' in file_path.lower():
                affected_by_type['config'].append(file_path)
            else:
                affected_by_type['other'].append(file_path)
        
        report = {
            'package': package_name,
            'version_change': f"{old_version} → {new_version}",
            'ecosystem': ecosystem,
            'impact_score': impact_score,
            'risk_level': self._get_risk_level(impact_score),
            'affected_files': {
                'total': len(usage),
                'by_type': dict(affected_by_type),
                'details': usage
            },
            'dependency_tree': tree_info,
            'breaking_changes': breaking_changes,
            'recommendations': self._generate_recommendations(impact_score, breaking_changes)
        }
        
        return report

    def _get_risk_level(self, impact_score: int) -> str:
        """Determine risk level based on impact score"""
        if impact_score >= 70:
            return 'high'
        elif impact_score >= 40:
            return 'medium'
        elif impact_score >= 20:
            return 'low'
        else:
            return 'minimal'

    def _generate_recommendations(self, impact_score: int, breaking_changes: List[str]) -> List[str]:
        """Generate recommendations based on analysis"""
        recommendations = []
        
        if impact_score >= 70:
            recommendations.append("High impact update - thorough testing required")
            recommendations.append("Consider staged rollout or canary deployment")
            recommendations.append("Review all affected files for compatibility")
        elif impact_score >= 40:
            recommendations.append("Medium impact - standard testing recommended")
            recommendations.append("Review critical path files")
        else:
            recommendations.append("Low impact - basic testing should suffice")
        
        if breaking_changes:
            recommendations.append("Breaking changes detected - manual review required")
            recommendations.append("Update code to accommodate API changes")
            recommendations.append("Consider creating a migration guide")
        
        return recommendations

def main():
    parser = argparse.ArgumentParser(description='Analyze dependency update impact')
    parser.add_argument('--package', '-p', required=True, help='Package name')
    parser.add_argument('--old-version', '-o', required=True, help='Old version')
    parser.add_argument('--new-version', '-n', required=True, help='New version')
    parser.add_argument('--repo-path', '-r', default='.', help='Repository path')
    parser.add_argument('--output', '-f', choices=['json', 'text'], default='text', help='Output format')
    parser.add_argument('--verbose', '-v', action='store_true', help='Verbose output')
    
    args = parser.parse_args()
    
    analyzer = DependencyImpactAnalyzer(args.repo_path)
    report = analyzer.generate_impact_report(args.package, args.old_version, args.new_version)
    
    if args.output == 'json':
        print(json.dumps(report, indent=2))
    else:
        # Text output
        print(f"\n{'='*60}")
        print(f"DEPENDENCY IMPACT ANALYSIS REPORT")
        print(f"{'='*60}")
        print(f"Package: {report['package']}")
        print(f"Version: {report['version_change']}")
        print(f"Ecosystem: {report['ecosystem']}")
        print(f"Impact Score: {report['impact_score']}/100")
        print(f"Risk Level: {report['risk_level'].upper()}")
        
        print(f"\n{'='*60}")
        print(f"AFFECTED FILES")
        print(f"{'='*60}")
        print(f"Total: {report['affected_files']['total']}")
        
        for category, files in report['affected_files']['by_type'].items():
            if files:
                print(f"\n{category.capitalize()} ({len(files)} files):")
                for file in files[:5]:  # Show first 5 files
                    print(f"  - {file}")
                if len(files) > 5:
                    print(f"  ... and {len(files) - 5} more")
        
        if args.verbose and report['affected_files']['details']:
            print(f"\n{'='*60}")
            print(f"DETAILED USAGE")
            print(f"{'='*60}")
            for file, lines in list(report['affected_files']['details'].items())[:10]:
                print(f"\n{file}:")
                for line in lines[:3]:
                    print(f"  {line}")
        
        print(f"\n{'='*60}")
        print(f"DEPENDENCY TREE IMPACT")
        print(f"{'='*60}")
        print(f"Direct dependents: {len(report['dependency_tree']['direct_dependents'])}")
        if report['dependency_tree']['direct_dependents']:
            print("  " + ", ".join(report['dependency_tree']['direct_dependents'][:5]))
        print(f"Total affected: {report['dependency_tree']['total_affected']}")
        
        if report['breaking_changes']:
            print(f"\n{'='*60}")
            print(f"⚠️  BREAKING CHANGES DETECTED")
            print(f"{'='*60}")
            for change in report['breaking_changes']:
                print(f"- {change}")
        
        print(f"\n{'='*60}")
        print(f"RECOMMENDATIONS")
        print(f"{'='*60}")
        for rec in report['recommendations']:
            print(f"• {rec}")
        
        print(f"\n{'='*60}\n")
        
        # Exit code based on risk level
        if report['risk_level'] == 'high':
            sys.exit(2)
        elif report['risk_level'] == 'medium':
            sys.exit(1)
        else:
            sys.exit(0)

if __name__ == '__main__':
    main()
