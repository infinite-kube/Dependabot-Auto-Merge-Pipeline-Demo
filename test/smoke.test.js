/**
 * Smoke tests for quick validation of critical functionality
 * These tests run quickly and verify the most important features
 */

const request = require('supertest');
const { app } = require('../../src/index');

describe('Smoke Tests', () => {
  describe('Critical Endpoints', () => {
    it('should have health check responding', async () => {
      const response = await request(app)
        .get('/health')
        .expect(200);
      
      expect(response.body.status).toBe('healthy');
    }, 5000); // 5 second timeout for smoke tests
    
    it('should have version endpoint responding', async () => {
      const response = await request(app)
        .get('/api/version')
        .expect(200);
      
      expect(response.body).toHaveProperty('version');
    }, 5000);
    
    it('should have authentication endpoint responding', async () => {
      const response = await request(app)
        .post('/api/auth/login')
        .send({
          username: 'test',
          password: 'test'
        });
      
      // We expect either 401 (invalid) or 200 (valid) but not 500 (error)
      expect(response.status).toBeLessThan(500);
    }, 5000);
  });
  
  describe('Application Stability', () => {
    it('should handle invalid routes gracefully', async () => {
      const response = await request(app)
        .get('/this/route/does/not/exist')
        .expect(404);
      
      expect(response.body).toHaveProperty('error');
    }, 5000);
    
    it('should handle malformed JSON gracefully', async () => {
      const response = await request(app)
        .post('/api/auth/login')
        .set('Content-Type', 'application/json')
        .send('{"invalid json}')
        .expect(400);
    }, 5000);
    
    it('should respond within acceptable time', async () => {
      const startTime = Date.now();
      
      await request(app)
        .get('/health')
        .expect(200);
      
      const responseTime = Date.now() - startTime;
      expect(responseTime).toBeLessThan(1000); // Should respond within 1 second
    }, 5000);
  });
  
  describe('Security Basics', () => {
    it('should not expose sensitive information in errors', async () => {
      const response = await request(app)
        .get('/api/cause-error')
        .expect(404);
      
      // Should not contain stack traces or internal paths
      expect(response.text).not.toMatch(/\/home\/|\/usr\/|stack|trace/i);
    }, 5000);
    
    it('should have security headers enabled', async () => {
      const response = await request(app)
        .get('/health')
        .expect(200);
      
      // Basic security headers from helmet
      expect(response.headers).toHaveProperty('x-content-type-options');
    }, 5000);
  });
  
  describe('Core Functionality', () => {
    it('should authenticate valid users', async () => {
      const response = await request(app)
        .post('/api/auth/login')
        .send({
          username: 'admin',
          password: 'password123'
        })
        .expect(200);
      
      expect(response.body).toHaveProperty('token');
      expect(response.body.token).toBeTruthy();
    }, 5000);
    
    it('should reject invalid authentication', async () => {
      const response = await request(app)
        .post('/api/auth/login')
        .send({
          username: 'invalid',
          password: 'invalid'
        })
        .expect(401);
      
      expect(response.body).toHaveProperty('error');
    }, 5000);
    
    it('should fetch external data', async () => {
      const response = await request(app)
        .get('/api/data')
        .timeout(5000);
      
      // Should either succeed or fail gracefully
      expect(response.status).toBeOneOf([200, 401, 500]);
      
      if (response.status === 200) {
        expect(response.body).toHaveProperty('id');
      }
    }, 10000); // Longer timeout for external calls
  });
});

describe('Quick Smoke Suite', () => {
  // Ultra-fast smoke tests for CI/CD pipeline
  it('should pass basic health checks', async () => {
    const criticalEndpoints = [
      '/health',
      '/api/version'
    ];
    
    const results = await Promise.all(
      criticalEndpoints.map(endpoint =>
        request(app).get(endpoint)
      )
    );
    
    results.forEach((response, index) => {
      expect(response.status).toBe(200);
      console.log(`✓ ${criticalEndpoints[index]} - OK`);
    });
  }, 3000);
});

// Custom matcher
expect.extend({
  toBeOneOf(received, expected) {
    const pass = expected.includes(received);
    return {
      pass,
      message: () => `Expected ${received} to be one of ${expected.join(', ')}`
    };
  }
});

// Export for use in CI/CD
module.exports = {
  runQuickSmoke: async () => {
    console.log('Running quick smoke tests...');
    
    const tests = [
      { endpoint: '/health', expected: 200 },
      { endpoint: '/api/version', expected: 200 }
    ];
    
    let passed = 0;
    let failed = 0;
    
    for (const test of tests) {
      try {
        const response = await request(app)
          .get(test.endpoint)
          .timeout(2000);
        
        if (response.status === test.expected) {
          console.log(`✓ ${test.endpoint}`);
          passed++;
        } else {
          console.log(`✗ ${test.endpoint} - Expected ${test.expected}, got ${response.status}`);
          failed++;
        }
      } catch (error) {
        console.log(`✗ ${test.endpoint} - ${error.message}`);
        failed++;
      }
    }
    
    console.log(`\nSmoke Tests: ${passed} passed, ${failed} failed`);
    return failed === 0;
  }
};
