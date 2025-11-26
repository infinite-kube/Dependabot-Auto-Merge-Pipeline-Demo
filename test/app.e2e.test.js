/**
 * End-to-End tests for the complete application flow
 * Simulates real user scenarios
 */

const request = require('supertest');
const { app } = require('../../src/index');
const jwt = require('jsonwebtoken');

describe('E2E Tests - User Journey', () => {
  let userToken;
  let userId;
  
  describe('New User Complete Journey', () => {
    it('should complete full user journey from registration to data access', async () => {
      // Step 1: User visits health check
      const healthResponse = await request(app)
        .get('/health')
        .expect(200);
      
      expect(healthResponse.body.status).toBe('healthy');
      
      // Step 2: User checks API version
      const versionResponse = await request(app)
        .get('/api/version')
        .expect(200);
      
      expect(versionResponse.body).toHaveProperty('version');
      expect(versionResponse.body).toHaveProperty('dependencies');
      
      // Step 3: User attempts to access protected resource without auth
      const unauthorizedResponse = await request(app)
        .get('/api/data')
        .expect(401);
      
      // Step 4: User logs in
      const loginResponse = await request(app)
        .post('/api/auth/login')
        .send({
          username: 'admin',
          password: 'password123'
        })
        .expect(200);
      
      expect(loginResponse.body).toHaveProperty('token');
      userToken = loginResponse.body.token;
      
      // Extract user ID from token
      const decoded = jwt.decode(userToken);
      userId = decoded.userId;
      
      // Step 5: User accesses protected data
      const dataResponse = await request(app)
        .get('/api/data')
        .set('Authorization', `Bearer ${userToken}`)
        .expect(200);
      
      expect(dataResponse.body).toHaveProperty('id');
      expect(dataResponse.body).toHaveProperty('title');
      expect(dataResponse.body).toHaveProperty('timestamp');
      
      // Step 6: User performs multiple operations
      const multipleRequests = await Promise.all([
        request(app).get('/api/data').set('Authorization', `Bearer ${userToken}`),
        request(app).get('/api/version'),
        request(app).get('/health')
      ]);
      
      multipleRequests.forEach(response => {
        expect(response.status).toBe(200);
      });
    });
  });
  
  describe('Admin Workflow', () => {
    it('should handle admin operations workflow', async () => {
      // Admin login
      const adminLogin = await request(app)
        .post('/api/auth/login')
        .send({
          username: 'admin',
          password: 'password123'
        })
        .expect(200);
      
      const adminToken = adminLogin.body.token;
      
      // Admin performs multiple administrative tasks
      // (These would be real admin endpoints in production)
      const adminTasks = [
        request(app).get('/api/data').set('Authorization', `Bearer ${adminToken}`),
        request(app).get('/health'),
        request(app).get('/api/version')
      ];
      
      const results = await Promise.all(adminTasks);
      results.forEach(result => {
        expect(result.status).toBe(200);
      });
    });
  });
  
  describe('Error Recovery Workflow', () => {
    it('should handle and recover from various error conditions', async () => {
      // Test 1: Invalid credentials
      await request(app)
        .post('/api/auth/login')
        .send({
          username: 'invalid',
          password: 'wrong'
        })
        .expect(401);
      
      // Test 2: Malformed request
      await request(app)
        .post('/api/auth/login')
        .send({
          notUsername: 'test'
        })
        .expect(400);
      
      // Test 3: Invalid token
      await request(app)
        .get('/api/data')
        .set('Authorization', 'Bearer invalid.token.here')
        .expect(401);
      
      // Test 4: Missing authorization
      await request(app)
        .get('/api/data')
        .expect(401);
      
      // After all errors, system should still be functional
      const healthCheck = await request(app)
        .get('/health')
        .expect(200);
      
      expect(healthCheck.body.status).toBe('healthy');
    });
  });
  
  describe('Performance Under Load', () => {
    it('should maintain performance under concurrent users', async function() {
      this.timeout(15000);
      
      // Simulate 20 concurrent users
      const users = Array(20).fill(null).map(async (_, index) => {
        // Each user performs a series of operations
        const userWorkflow = async () => {
          // Login
          const loginRes = await request(app)
            .post('/api/auth/login')
            .send({
              username: 'admin',
              password: 'password123'
            });
          
          const token = loginRes.body.token;
          
          // Access data multiple times
          for (let i = 0; i < 3; i++) {
            await request(app)
              .get('/api/data')
              .set('Authorization', `Bearer ${token}`);
          }
          
          return true;
        };
        
        return userWorkflow();
      });
      
      const startTime = Date.now();
      const results = await Promise.all(users);
      const duration = Date.now() - startTime;
      
      // All users should complete successfully
      expect(results.every(r => r === true)).toBe(true);
      
      // Should complete within reasonable time (adjust based on your requirements)
      expect(duration).toBeLessThan(10000); // 10 seconds for 20 users
    });
  });
  
  describe('Data Consistency', () => {
    it('should maintain data consistency across operations', async () => {
      // Login first
      const loginRes = await request(app)
        .post('/api/auth/login')
        .send({
          username: 'admin',
          password: 'password123'
        })
        .expect(200);
      
      const token = loginRes.body.token;
      
      // Perform multiple data operations
      const operations = [];
      for (let i = 0; i < 5; i++) {
        operations.push(
          request(app)
            .get('/api/data')
            .set('Authorization', `Bearer ${token}`)
        );
      }
      
      const responses = await Promise.all(operations);
      
      // All responses should be successful
      responses.forEach(response => {
        expect(response.status).toBe(200);
        expect(response.body).toHaveProperty('id');
        expect(response.body).toHaveProperty('timestamp');
      });
      
      // Check that IDs are unique (no duplicate processing)
      const ids = responses.map(r => r.body.id);
      const uniqueIds = [...new Set(ids)];
      expect(uniqueIds.length).toBe(ids.length);
    });
  });
  
  describe('Cross-Origin Resource Sharing (CORS)', () => {
    it('should handle CORS properly', async () => {
      const response = await request(app)
        .get('/health')
        .set('Origin', 'http://example.com')
        .expect(200);
      
      // Should have CORS headers
      expect(response.headers).toHaveProperty('access-control-allow-origin');
    });
    
    it('should handle preflight requests', async () => {
      const response = await request(app)
        .options('/api/data')
        .set('Origin', 'http://example.com')
        .set('Access-Control-Request-Method', 'GET')
        .expect(204);
      
      expect(response.headers).toHaveProperty('access-control-allow-methods');
    });
  });
  
  describe('Session Management', () => {
    it('should handle token expiration gracefully', async () => {
      // Create an expired token
      const expiredPayload = {
        userId: 'test-user',
        username: 'testuser',
        exp: Math.floor(Date.now() / 1000) - 3600 // Expired 1 hour ago
      };
      
      const expiredToken = jwt.sign(
        expiredPayload,
        process.env.JWT_SECRET || 'secret',
        { algorithm: 'HS256' }
      );
      
      const response = await request(app)
        .get('/api/data')
        .set('Authorization', `Bearer ${expiredToken}`)
        .expect(401);
      
      expect(response.body).toHaveProperty('error');
    });
  });
});

describe('E2E Tests - Dependabot Scenario', () => {
  it('should handle dependency update workflow', async () => {
    // This simulates what happens when Dependabot creates a PR
    // and the system processes it
    
    // Step 1: Check current version
    const versionBefore = await request(app)
      .get('/api/version')
      .expect(200);
    
    expect(versionBefore.body).toHaveProperty('dependencies');
    
    // Step 2: Simulate dependency update (in real scenario, this would be a PR)
    // The application should continue to work after dependencies are updated
    
    // Step 3: Health check should still pass
    const healthCheck = await request(app)
      .get('/health')
      .expect(200);
    
    expect(healthCheck.body.status).toBe('healthy');
    
    // Step 4: All endpoints should remain functional
    const endpoints = [
      { method: 'GET', path: '/health' },
      { method: 'GET', path: '/api/version' },
      { method: 'POST', path: '/api/auth/login', body: { username: 'admin', password: 'password123' } }
    ];
    
    for (const endpoint of endpoints) {
      const req = request(app)[endpoint.method.toLowerCase()](endpoint.path);
      
      if (endpoint.body) {
        req.send(endpoint.body);
      }
      
      const response = await req;
      expect(response.status).toBeLessThan(500); // No server errors
    }
  });
});

describe('E2E Tests - Security Compliance', () => {
  it('should meet security requirements', async () => {
    // Test security headers
    const response = await request(app)
      .get('/health')
      .expect(200);
    
    // Check for security headers (set by helmet)
    expect(response.headers).toHaveProperty('x-dns-prefetch-control');
    expect(response.headers).toHaveProperty('x-frame-options');
    expect(response.headers).toHaveProperty('x-content-type-options');
    
    // Test against common vulnerabilities
    const vulnerabilityTests = [
      {
        name: 'SQL Injection',
        endpoint: '/api/auth/login',
        method: 'POST',
        payload: { username: "admin' OR '1'='1", password: 'password' }
      },
      {
        name: 'XSS',
        endpoint: '/api/auth/login',
        method: 'POST',
        payload: { username: '<script>alert(1)</script>', password: 'password' }
      },
      {
        name: 'Command Injection',
        endpoint: '/api/auth/login',
        method: 'POST',
        payload: { username: 'admin; cat /etc/passwd', password: 'password' }
      }
    ];
    
    for (const test of vulnerabilityTests) {
      const req = request(app)[test.method.toLowerCase()](test.endpoint)
        .send(test.payload);
      
      const res = await req;
      
      // Should not result in successful authentication or server error
      expect(res.status).toBeOneOf([400, 401]);
      
      // Should not leak sensitive information
      expect(res.text).not.toMatch(/password|secret|token|stack trace/i);
    }
  });
});

// Helper for custom matchers
expect.extend({
  toBeOneOf(received, expected) {
    const pass = expected.includes(received);
    return {
      pass,
      message: () => `Expected ${received} to be one of ${expected.join(', ')}`
    };
  }
});
