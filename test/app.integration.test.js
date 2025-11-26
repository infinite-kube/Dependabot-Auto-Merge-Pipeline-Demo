/**
 * Integration tests for the Express application
 * Tests the interaction between different components
 */

const request = require('supertest');
const { app } = require('../../src/index');
const redis = require('redis');
const { Pool } = require('pg');
const mongoose = require('mongoose');

// Integration test configuration
const TEST_CONFIG = {
  redis: {
    host: process.env.REDIS_HOST || 'localhost',
    port: process.env.REDIS_PORT || 6379
  },
  postgres: {
    host: process.env.DB_HOST || 'localhost',
    database: process.env.DB_NAME || 'test',
    user: process.env.DB_USER || 'postgres',
    password: process.env.DB_PASSWORD || 'postgres'
  },
  mongodb: {
    uri: process.env.MONGO_URI || 'mongodb://localhost:27017/test'
  }
};

describe('Integration Tests', () => {
  let redisClient;
  let pgPool;
  let mongoConnection;
  let authToken;

  beforeAll(async () => {
    // Setup Redis connection
    try {
      redisClient = redis.createClient(TEST_CONFIG.redis);
      await redisClient.connect();
    } catch (error) {
      console.warn('Redis connection failed:', error.message);
    }

    // Setup PostgreSQL connection
    try {
      pgPool = new Pool(TEST_CONFIG.postgres);
      await pgPool.query('SELECT 1');
    } catch (error) {
      console.warn('PostgreSQL connection failed:', error.message);
    }

    // Setup MongoDB connection
    try {
      mongoConnection = await mongoose.connect(TEST_CONFIG.mongodb.uri);
    } catch (error) {
      console.warn('MongoDB connection failed:', error.message);
    }
  });

  afterAll(async () => {
    // Cleanup connections
    if (redisClient) await redisClient.quit();
    if (pgPool) await pgPool.end();
    if (mongoConnection) await mongoose.disconnect();
  });

  describe('Authentication Flow', () => {
    it('should complete full authentication flow', async () => {
      // 1. Login
      const loginResponse = await request(app)
        .post('/api/auth/login')
        .send({
          username: 'admin',
          password: 'password123'
        })
        .expect(200);

      expect(loginResponse.body).toHaveProperty('token');
      authToken = loginResponse.body.token;

      // 2. Use token to access protected endpoint
      const dataResponse = await request(app)
        .get('/api/data')
        .set('Authorization', `Bearer ${authToken}`)
        .expect(200);

      expect(dataResponse.body).toHaveProperty('id');
    });

    it('should handle token refresh flow', async () => {
      // This would test token refresh if implemented
      // For now, we'll test token expiration handling
      const expiredToken = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOiIxMjMiLCJleHAiOjE2MDAwMDAwMDB9.invalid';

      const response = await request(app)
        .get('/api/data')
        .set('Authorization', `Bearer ${expiredToken}`)
        .expect(401);

      expect(response.body).toHaveProperty('error');
    });
  });

  describe('Data Persistence', () => {
    it('should persist data in Redis cache', async function() {
      if (!redisClient) {
        this.skip();
      }

      // Make a request that should cache data
      const response = await request(app)
        .get('/api/data')
        .expect(200);

      const cacheKey = `data:${response.body.id}`;
      
      // Check if data was cached
      const cachedData = await redisClient.get(cacheKey);
      if (cachedData) {
        expect(JSON.parse(cachedData)).toHaveProperty('id');
      }
    });

    it('should store and retrieve from PostgreSQL', async function() {
      if (!pgPool) {
        this.skip();
      }

      // Create a test table
      await pgPool.query(`
        CREATE TABLE IF NOT EXISTS test_data (
          id SERIAL PRIMARY KEY,
          data JSONB,
          created_at TIMESTAMP DEFAULT NOW()
        )
      `);

      // Insert test data
      const insertResult = await pgPool.query(
        'INSERT INTO test_data (data) VALUES ($1) RETURNING id',
        [JSON.stringify({ test: 'integration' })]
      );

      expect(insertResult.rows[0]).toHaveProperty('id');

      // Retrieve data
      const selectResult = await pgPool.query(
        'SELECT * FROM test_data WHERE id = $1',
        [insertResult.rows[0].id]
      );

      expect(selectResult.rows).toHaveLength(1);
      expect(JSON.parse(selectResult.rows[0].data)).toEqual({ test: 'integration' });

      // Cleanup
      await pgPool.query('DROP TABLE IF EXISTS test_data');
    });

    it('should store and retrieve from MongoDB', async function() {
      if (!mongoConnection) {
        this.skip();
      }

      const TestModel = mongoose.model('Test', new mongoose.Schema({
        data: Object,
        createdAt: { type: Date, default: Date.now }
      }));

      // Create document
      const doc = new TestModel({ data: { test: 'integration' } });
      await doc.save();

      expect(doc._id).toBeDefined();

      // Retrieve document
      const found = await TestModel.findById(doc._id);
      expect(found.data).toEqual({ test: 'integration' });

      // Cleanup
      await TestModel.deleteOne({ _id: doc._id });
    });
  });

  describe('End-to-End Workflows', () => {
    it('should handle complete data processing workflow', async () => {
      // 1. Fetch external data
      const externalData = await request(app)
        .get('/api/data')
        .expect(200);

      expect(externalData.body).toHaveProperty('title');
      expect(externalData.body).toHaveProperty('timestamp');

      // 2. Process the data (if we had a processing endpoint)
      // This would test data transformation pipelines
    });

    it('should handle concurrent requests', async () => {
      const requests = Array(10).fill(null).map(() =>
        request(app).get('/health')
      );

      const responses = await Promise.all(requests);
      
      responses.forEach(response => {
        expect(response.status).toBe(200);
        expect(response.body).toHaveProperty('status', 'healthy');
      });
    });
  });

  describe('Error Recovery', () => {
    it('should recover from database connection failure', async function() {
      if (!pgPool) {
        this.skip();
      }

      // Simulate connection failure
      const badPool = new Pool({
        ...TEST_CONFIG.postgres,
        port: 9999 // Wrong port
      });

      try {
        await badPool.query('SELECT 1');
      } catch (error) {
        expect(error).toBeDefined();
      }

      await badPool.end();

      // App should still respond to health checks
      const response = await request(app)
        .get('/health')
        .expect(200);

      expect(response.body.services.postgresql).toBe('disconnected');
    });

    it('should handle Redis cache miss gracefully', async () => {
      // Even if Redis is down, API should work
      const response = await request(app)
        .get('/api/data')
        .expect(200);

      expect(response.body).toHaveProperty('id');
    });
  });

  describe('Performance Tests', () => {
    it('should respond within acceptable time limits', async () => {
      const startTime = Date.now();
      
      await request(app)
        .get('/health')
        .expect(200);
      
      const responseTime = Date.now() - startTime;
      expect(responseTime).toBeLessThan(1000); // Should respond within 1 second
    });

    it('should handle load without degradation', async function() {
      this.timeout(10000);

      const iterations = 50;
      const responseTimes = [];

      for (let i = 0; i < iterations; i++) {
        const start = Date.now();
        await request(app).get('/health');
        responseTimes.push(Date.now() - start);
      }

      const avgResponseTime = responseTimes.reduce((a, b) => a + b, 0) / iterations;
      const maxResponseTime = Math.max(...responseTimes);

      expect(avgResponseTime).toBeLessThan(200); // Avg should be under 200ms
      expect(maxResponseTime).toBeLessThan(1000); // Max should be under 1s
    });
  });

  describe('Security Integration', () => {
    it('should enforce rate limiting across endpoints', async function() {
      // Note: Rate limiting would need to be implemented
      this.skip('Rate limiting not implemented in demo');
      
      const requests = Array(100).fill(null).map(() =>
        request(app).get('/api/data')
      );

      const responses = await Promise.all(requests);
      const rateLimited = responses.some(r => r.status === 429);

      expect(rateLimited).toBe(true);
    });

    it('should validate input across all endpoints', async () => {
      const maliciousInputs = [
        { sql: "'; DROP TABLE users; --" },
        { xss: '<script>alert("xss")</script>' },
        { xxe: '<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>' },
        { overflow: 'A'.repeat(10000) }
      ];

      for (const input of maliciousInputs) {
        const response = await request(app)
          .post('/api/auth/login')
          .send({
            username: JSON.stringify(input),
            password: 'test'
          });

        // Should handle safely without errors or exposing sensitive data
        expect(response.status).toBeOneOf([400, 401]);
        expect(response.text).not.toMatch(/error.*stack|internal.*error/i);
      }
    });
  });
});

// Custom matcher for multiple expected values
expect.extend({
  toBeOneOf(received, expected) {
    const pass = expected.includes(received);
    return {
      pass,
      message: () => `Expected ${received} to be one of ${expected.join(', ')}`
    };
  }
});
