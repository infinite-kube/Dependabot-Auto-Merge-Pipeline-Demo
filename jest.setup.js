/**
 * Jest setup file
 * Runs before all tests
 */

// Set test environment
process.env.NODE_ENV = 'test';
process.env.JWT_SECRET = 'test-secret-key';
process.env.PORT = 0; // Use random port for tests

// Mock console methods to reduce noise in tests
const originalConsole = {
  log: console.log,
  error: console.error,
  warn: console.warn
};

// Only show console output if DEBUG_TESTS is set
if (!process.env.DEBUG_TESTS) {
  console.log = jest.fn();
  console.error = jest.fn();
  console.warn = jest.fn();
}

// Global test utilities
global.testUtils = {
  // Generate test data
  generateUser: (overrides = {}) => ({
    username: 'testuser',
    password: 'testpass123',
    email: 'test@example.com',
    ...overrides
  }),
  
  // Generate auth token
  generateToken: () => {
    const jwt = require('jsonwebtoken');
    return jwt.sign(
      { userId: 'test-123', username: 'testuser' },
      process.env.JWT_SECRET,
      { expiresIn: '1h' }
    );
  },
  
  // Clean up function
  cleanup: async () => {
    // Clean up any test data, connections, etc.
    // This would be called in afterEach or afterAll
  },
  
  // Restore console
  restoreConsole: () => {
    console.log = originalConsole.log;
    console.error = originalConsole.error;
    console.warn = originalConsole.warn;
  }
};

// Set longer timeout for CI environments
if (process.env.CI) {
  jest.setTimeout(30000);
}

// Mock external services
jest.mock('axios', () => ({
  get: jest.fn(() => Promise.resolve({ data: {} })),
  post: jest.fn(() => Promise.resolve({ data: {} })),
  put: jest.fn(() => Promise.resolve({ data: {} })),
  delete: jest.fn(() => Promise.resolve({ data: {} }))
}));

// Mock Redis if not in integration tests
if (process.env.TEST_TYPE !== 'integration') {
  jest.mock('redis', () => ({
    createClient: jest.fn(() => ({
      connect: jest.fn(),
      get: jest.fn(),
      set: jest.fn(),
      setex: jest.fn(),
      hset: jest.fn(),
      hget: jest.fn(),
      del: jest.fn(),
      quit: jest.fn(),
      ping: jest.fn(() => 'PONG')
    }))
  }));
}

// Handle unhandled promise rejections
process.on('unhandledRejection', (reason, promise) => {
  console.error('Unhandled Rejection in tests at:', promise, 'reason:', reason);
});

// Export for use in tests
module.exports = {
  originalConsole,
  testUtils: global.testUtils
};
