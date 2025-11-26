/**
 * Unit tests for the Express application
 */

const request = require('supertest');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcrypt');
const { app, utilities, logger } = require('../src/index');

// Mock external dependencies
jest.mock('axios');
jest.mock('redis');

describe('Express Application', () => {
  describe('GET /health', () => {
    it('should return health status', async () => {
      const response = await request(app)
        .get('/health')
        .expect(200);

      expect(response.body).toHaveProperty('status', 'healthy');
      expect(response.body).toHaveProperty('timestamp');
      expect(response.body).toHaveProperty('uptime');
      expect(response.body).toHaveProperty('version');
    });

    it('should return JSON content type', async () => {
      const response = await request(app)
        .get('/health')
        .expect('Content-Type', /json/);
    });
  });

  describe('GET /api/version', () => {
    it('should return version information', async () => {
      const response = await request(app)
        .get('/api/version')
        .expect(200);

      expect(response.body).toHaveProperty('version');
      expect(response.body).toHaveProperty('dependencies');
      expect(response.body).toHaveProperty('nodeVersion');
      expect(typeof response.body.dependencies).toBe('object');
    });
  });

  describe('POST /api/auth/login', () => {
    it('should reject login with missing credentials', async () => {
      const response = await request(app)
        .post('/api/auth/login')
        .send({})
        .expect(400);

      expect(response.body).toHaveProperty('error', 'Username and password required');
    });

    it('should reject login with invalid credentials', async () => {
      const response = await request(app)
        .post('/api/auth/login')
        .send({
          username: 'wronguser',
          password: 'wrongpassword'
        })
        .expect(401);

      expect(response.body).toHaveProperty('error', 'Invalid credentials');
    });

    it('should accept valid admin credentials', async () => {
      const response = await request(app)
        .post('/api/auth/login')
        .send({
          username: 'admin',
          password: 'password123'
        })
        .expect(200);

      expect(response.body).toHaveProperty('token');
      expect(response.body).toHaveProperty('expiresIn', 3600);

      // Verify token is valid
      const decoded = jwt.verify(response.body.token, process.env.JWT_SECRET || 'secret');
      expect(decoded).toHaveProperty('username', 'admin');
    });
  });

  describe('GET /api/data', () => {
    const axios = require('axios');

    beforeEach(() => {
      axios.get = jest.fn();
    });

    it('should fetch and process external data', async () => {
      axios.get.mockResolvedValue({
        data: {
          userId: 1,
          id: 1,
          title: 'Test Post',
          body: 'Test Body'
        }
      });

      const response = await request(app)
        .get('/api/data')
        .expect(200);

      expect(response.body).toHaveProperty('title', 'Test Post');
      expect(response.body).toHaveProperty('body', 'Test Body');
      expect(response.body).toHaveProperty('timestamp');
      expect(response.body).toHaveProperty('id');
    });

    it('should handle external API errors gracefully', async () => {
      axios.get.mockRejectedValue(new Error('Network error'));

      const response = await request(app)
        .get('/api/data')
        .expect(500);

      expect(response.body).toHaveProperty('error', 'Internal server error');
    });
  });

  describe('404 Handler', () => {
    it('should return 404 for unknown routes', async () => {
      const response = await request(app)
        .get('/unknown/route')
        .expect(404);

      expect(response.body).toHaveProperty('error', 'Not found');
      expect(response.body).toHaveProperty('path', '/unknown/route');
    });
  });
});

describe('Utility Functions', () => {
  describe('generateId', () => {
    it('should generate unique IDs', () => {
      const id1 = utilities.generateId();
      const id2 = utilities.generateId();

      expect(id1).not.toBe(id2);
      expect(typeof id1).toBe('string');
      expect(id1).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    });
  });

  describe('formatDate', () => {
    it('should format dates correctly', () => {
      const date = new Date('2024-01-15T10:30:00Z');
      const formatted = utilities.formatDate(date);

      expect(formatted).toBe('2024-01-15');
    });
  });

  describe('sanitizeData', () => {
    it('should remove sensitive fields', () => {
      const data = {
        username: 'user',
        password: 'secret123',
        email: 'user@example.com',
        token: 'jwt-token',
        secret: 'api-secret'
      };

      const sanitized = utilities.sanitizeData(data);

      expect(sanitized).toHaveProperty('username');
      expect(sanitized).toHaveProperty('email');
      expect(sanitized).not.toHaveProperty('password');
      expect(sanitized).not.toHaveProperty('token');
      expect(sanitized).not.toHaveProperty('secret');
    });
  });

  describe('Password utilities', () => {
    it('should hash passwords', async () => {
      const password = 'testPassword123';
      const hash = await utilities.hashPassword(password);

      expect(hash).not.toBe(password);
      expect(hash).toMatch(/^\$2[aby]\$.{56}$/);
    });

    it('should verify passwords correctly', async () => {
      const password = 'testPassword123';
      const hash = await utilities.hashPassword(password);

      const isValid = await utilities.verifyPassword(password, hash);
      const isInvalid = await utilities.verifyPassword('wrongPassword', hash);

      expect(isValid).toBe(true);
      expect(isInvalid).toBe(false);
    });
  });

  describe('Token utilities', () => {
    it('should generate valid tokens', () => {
      const payload = { userId: '123', role: 'user' };
      const token = utilities.generateToken(payload);

      expect(typeof token).toBe('string');
      expect(token.split('.')).toHaveLength(3);
    });

    it('should verify valid tokens', () => {
      const payload = { userId: '123', role: 'user' };
      const token = utilities.generateToken(payload);
      const verified = utilities.verifyToken(token);

      expect(verified).toHaveProperty('userId', '123');
      expect(verified).toHaveProperty('role', 'user');
    });

    it('should return null for invalid tokens', () => {
      const verified = utilities.verifyToken('invalid.token.here');

      expect(verified).toBeNull();
    });
  });

  describe('HTTP utilities', () => {
    const axios = require('axios');

    beforeEach(() => {
      axios.mockClear();
    });

    it('should make HTTP requests', async () => {
      axios.mockResolvedValue({ data: { success: true } });

      const result = await utilities.makeHttpRequest('https://api.example.com/data');

      expect(result).toEqual({ success: true });
      expect(axios).toHaveBeenCalledWith({
        url: 'https://api.example.com/data'
      });
    });

    it('should pass options to axios', async () => {
      axios.mockResolvedValue({ data: { success: true } });

      await utilities.makeHttpRequest('https://api.example.com/data', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer token' }
      });

      expect(axios).toHaveBeenCalledWith({
        url: 'https://api.example.com/data',
        method: 'POST',
        headers: { 'Authorization': 'Bearer token' }
      });
    });

    it('should handle HTTP errors', async () => {
      axios.mockRejectedValue(new Error('Network error'));

      await expect(utilities.makeHttpRequest('https://api.example.com/data'))
        .rejects.toThrow('Network error');
    });
  });
});

describe('Security Tests', () => {
  it('should not expose sensitive data in errors', async () => {
    const response = await request(app)
      .get('/api/data')
      .expect(500);

    expect(JSON.stringify(response.body)).not.toContain('password');
    expect(JSON.stringify(response.body)).not.toContain('secret');
    expect(JSON.stringify(response.body)).not.toContain('token');
  });

  it('should handle SQL injection attempts safely', async () => {
    const maliciousInput = "'; DROP TABLE users; --";
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        username: maliciousInput,
        password: 'password'
      })
      .expect(401);

    expect(response.body).toHaveProperty('error', 'Invalid credentials');
  });

  it('should handle XSS attempts', async () => {
    const xssPayload = '<script>alert("XSS")</script>';
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        username: xssPayload,
        password: 'password'
      })
      .expect(401);

    expect(response.body).not.toContain('<script>');
  });
});
