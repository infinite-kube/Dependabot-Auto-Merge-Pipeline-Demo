/**
 * Jest setup file that runs after the test framework has been installed
 * Used for adding custom matchers and global test configuration
 */

// Add custom matchers
expect.extend({
  // Check if value is one of expected values
  toBeOneOf(received, expected) {
    const pass = expected.includes(received);
    return {
      pass,
      message: () =>
        pass
          ? `Expected ${received} not to be one of ${expected.join(', ')}`
          : `Expected ${received} to be one of ${expected.join(', ')}`
    };
  },
  
  // Check if response time is acceptable
  toBeWithinResponseTime(received, threshold) {
    const pass = received < threshold;
    return {
      pass,
      message: () =>
        pass
          ? `Expected response time ${received}ms to be greater than ${threshold}ms`
          : `Expected response time ${received}ms to be within ${threshold}ms`
    };
  },
  
  // Check if error rate is acceptable
  toHaveAcceptableErrorRate(received, maxRate) {
    const pass = received <= maxRate;
    return {
      pass,
      message: () =>
        pass
          ? `Expected error rate ${received}% to be greater than ${maxRate}%`
          : `Expected error rate ${received}% to be less than or equal to ${maxRate}%`
    };
  },
  
  // Check if object has required security headers
  toHaveSecurityHeaders(received) {
    const requiredHeaders = [
      'x-content-type-options',
      'x-frame-options',
      'x-xss-protection'
    ];
    
    const missingHeaders = requiredHeaders.filter(
      header => !received.hasOwnProperty(header)
    );
    
    const pass = missingHeaders.length === 0;
    
    return {
      pass,
      message: () =>
        pass
          ? `Expected not to have security headers`
          : `Missing security headers: ${missingHeaders.join(', ')}`
    };
  }
});

// Global beforeEach
beforeEach(() => {
  // Reset all mocks before each test
  jest.clearAllMocks();
  
  // Clear any test-specific environment variables
  delete process.env.TEST_SPECIFIC_VAR;
});

// Global afterEach
afterEach(() => {
  // Clean up after each test if needed
});

// Increase timeout for slow CI environments
if (process.env.CI) {
  jest.setTimeout(30000);
} else {
  jest.setTimeout(10000);
}

// Suppress specific warnings in tests
const originalWarn = console.warn;
console.warn = (...args) => {
  if (
    args[0]?.includes?.('Warning: ReactDOM.render') ||
    args[0]?.includes?.('deprecation')
  ) {
    return;
  }
  originalWarn.apply(console, args);
};

// Add global test helpers
global.waitFor = (condition, timeout = 5000) => {
  return new Promise((resolve, reject) => {
    const interval = 100;
    let elapsed = 0;
    
    const check = () => {
      if (condition()) {
        resolve();
      } else if (elapsed >= timeout) {
        reject(new Error('Timeout waiting for condition'));
      } else {
        elapsed += interval;
        setTimeout(check, interval);
      }
    };
    
    check();
  });
};

// Mock timers for specific tests
global.enableMockTimers = () => {
  jest.useFakeTimers();
};

global.disableMockTimers = () => {
  jest.useRealTimers();
};

// Export for use in tests
module.exports = {
  // Any exports needed by tests
};
