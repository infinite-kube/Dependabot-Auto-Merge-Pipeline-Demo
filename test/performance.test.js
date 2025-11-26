/**
 * Performance tests to ensure application meets performance requirements
 * Especially important when updating dependencies
 */

const request = require('supertest');
const { app } = require('../../src/index');
const { performance } = require('perf_hooks');

describe('Performance Tests', () => {
  // Test configuration
  const PERFORMANCE_THRESHOLDS = {
    health: 100,        // Health check should respond in < 100ms
    api: 500,          // API endpoints should respond in < 500ms
    auth: 1000,        // Auth endpoints can take up to 1s (bcrypt)
    external: 3000,    // External API calls can take up to 3s
    p95: 1000,         // 95th percentile should be under 1s
    p99: 2000          // 99th percentile should be under 2s
  };
  
  describe('Response Time Tests', () => {
    it('should respond to health checks quickly', async () => {
      const times = [];
      
      for (let i = 0; i < 10; i++) {
        const start = performance.now();
        await request(app).get('/health').expect(200);
        const duration = performance.now() - start;
        times.push(duration);
      }
      
      const avgTime = times.reduce((a, b) => a + b, 0) / times.length;
      console.log(`Health check avg response time: ${avgTime.toFixed(2)}ms`);
      
      expect(avgTime).toBeLessThan(PERFORMANCE_THRESHOLDS.health);
    });
    
    it('should handle API requests within threshold', async () => {
      const times = [];
      
      for (let i = 0; i < 10; i++) {
        const start = performance.now();
        await request(app).get('/api/version');
        const duration = performance.now() - start;
        times.push(duration);
      }
      
      const avgTime = times.reduce((a, b) => a + b, 0) / times.length;
      console.log(`API avg response time: ${avgTime.toFixed(2)}ms`);
      
      expect(avgTime).toBeLessThan(PERFORMANCE_THRESHOLDS.api);
    });
    
    it('should handle authentication within threshold', async () => {
      const times = [];
      
      for (let i = 0; i < 5; i++) {
        const start = performance.now();
        await request(app)
          .post('/api/auth/login')
          .send({ username: 'admin', password: 'password123' });
        const duration = performance.now() - start;
        times.push(duration);
      }
      
      const avgTime = times.reduce((a, b) => a + b, 0) / times.length;
      console.log(`Auth avg response time: ${avgTime.toFixed(2)}ms`);
      
      expect(avgTime).toBeLessThan(PERFORMANCE_THRESHOLDS.auth);
    });
  });
  
  describe('Load Tests', () => {
    it('should handle concurrent requests', async function() {
      this.timeout(30000);
      
      const concurrentUsers = 20;
      const requestsPerUser = 5;
      const times = [];
      
      const userSimulation = async () => {
        const userTimes = [];
        
        for (let i = 0; i < requestsPerUser; i++) {
          const start = performance.now();
          await request(app).get('/health');
          const duration = performance.now() - start;
          userTimes.push(duration);
        }
        
        return userTimes;
      };
      
      const users = Array(concurrentUsers).fill(null).map(() => userSimulation());
      const results = await Promise.all(users);
      
      // Flatten all times
      results.forEach(userTimes => times.push(...userTimes));
      
      // Calculate statistics
      const avgTime = times.reduce((a, b) => a + b, 0) / times.length;
      const sortedTimes = times.sort((a, b) => a - b);
      const p50 = sortedTimes[Math.floor(times.length * 0.5)];
      const p95 = sortedTimes[Math.floor(times.length * 0.95)];
      const p99 = sortedTimes[Math.floor(times.length * 0.99)];
      
      console.log(`Load test results (${concurrentUsers} users, ${requestsPerUser} requests each):`);
      console.log(`  Average: ${avgTime.toFixed(2)}ms`);
      console.log(`  P50: ${p50.toFixed(2)}ms`);
      console.log(`  P95: ${p95.toFixed(2)}ms`);
      console.log(`  P99: ${p99.toFixed(2)}ms`);
      
      expect(p95).toBeLessThan(PERFORMANCE_THRESHOLDS.p95);
      expect(p99).toBeLessThan(PERFORMANCE_THRESHOLDS.p99);
    });
    
    it('should maintain performance under sustained load', async function() {
      this.timeout(60000);
      
      const duration = 10000; // 10 seconds
      const requestsPerSecond = 10;
      const times = [];
      let errors = 0;
      
      const startTime = Date.now();
      const interval = 1000 / requestsPerSecond;
      
      while (Date.now() - startTime < duration) {
        const requestStart = performance.now();
        
        try {
          await request(app).get('/health').timeout(5000);
          const requestDuration = performance.now() - requestStart;
          times.push(requestDuration);
        } catch (error) {
          errors++;
        }
        
        await new Promise(resolve => setTimeout(resolve, interval));
      }
      
      const avgTime = times.reduce((a, b) => a + b, 0) / times.length;
      const errorRate = (errors / (times.length + errors)) * 100;
      
      console.log(`Sustained load test results:`);
      console.log(`  Total requests: ${times.length + errors}`);
      console.log(`  Successful: ${times.length}`);
      console.log(`  Failed: ${errors}`);
      console.log(`  Error rate: ${errorRate.toFixed(2)}%`);
      console.log(`  Avg response time: ${avgTime.toFixed(2)}ms`);
      
      expect(errorRate).toBeLessThan(1); // Less than 1% error rate
      expect(avgTime).toBeLessThan(PERFORMANCE_THRESHOLDS.api);
    });
  });
  
  describe('Memory Tests', () => {
    it('should not leak memory under load', async function() {
      this.timeout(30000);
      
      if (!global.gc) {
        console.log('Skipping memory test (run with --expose-gc flag)');
        this.skip();
      }
      
      const iterations = 100;
      const memorySnapshots = [];
      
      // Force garbage collection and take initial snapshot
      global.gc();
      const initialMemory = process.memoryUsage();
      memorySnapshots.push(initialMemory.heapUsed);
      
      // Perform many requests
      for (let i = 0; i < iterations; i++) {
        await request(app).get('/health');
        
        if (i % 20 === 0) {
          global.gc();
          const memory = process.memoryUsage();
          memorySnapshots.push(memory.heapUsed);
        }
      }
      
      // Final garbage collection and snapshot
      global.gc();
      const finalMemory = process.memoryUsage();
      memorySnapshots.push(finalMemory.heapUsed);
      
      // Calculate memory growth
      const memoryGrowth = finalMemory.heapUsed - initialMemory.heapUsed;
      const memoryGrowthMB = memoryGrowth / 1024 / 1024;
      
      console.log(`Memory test results:`);
      console.log(`  Initial heap: ${(initialMemory.heapUsed / 1024 / 1024).toFixed(2)} MB`);
      console.log(`  Final heap: ${(finalMemory.heapUsed / 1024 / 1024).toFixed(2)} MB`);
      console.log(`  Growth: ${memoryGrowthMB.toFixed(2)} MB`);
      
      // Memory growth should be minimal (less than 10MB for this test)
      expect(Math.abs(memoryGrowthMB)).toBeLessThan(10);
    });
  });
  
  describe('Stress Tests', () => {
    it('should handle rapid-fire requests', async function() {
      this.timeout(20000);
      
      const requests = 100;
      const promises = [];
      
      // Fire all requests at once
      for (let i = 0; i < requests; i++) {
        promises.push(
          request(app)
            .get('/health')
            .then(res => ({ success: true, status: res.status }))
            .catch(err => ({ success: false, error: err.message }))
        );
      }
      
      const results = await Promise.all(promises);
      
      const successful = results.filter(r => r.success).length;
      const failed = results.filter(r => !r.success).length;
      const successRate = (successful / requests) * 100;
      
      console.log(`Stress test results:`);
      console.log(`  Total requests: ${requests}`);
      console.log(`  Successful: ${successful}`);
      console.log(`  Failed: ${failed}`);
      console.log(`  Success rate: ${successRate.toFixed(2)}%`);
      
      expect(successRate).toBeGreaterThan(95); // At least 95% success rate
    });
    
    it('should handle large payloads', async () => {
      const largePayload = {
        username: 'admin',
        password: 'password123',
        data: 'x'.repeat(10000) // 10KB of data
      };
      
      const start = performance.now();
      const response = await request(app)
        .post('/api/auth/login')
        .send(largePayload);
      const duration = performance.now() - start;
      
      console.log(`Large payload response time: ${duration.toFixed(2)}ms`);
      
      expect(response.status).toBeLessThan(500); // Should not error
      expect(duration).toBeLessThan(2000); // Should still be reasonably fast
    });
  });
  
  describe('Dependency Update Impact', () => {
    it('should maintain performance after dependency updates', async () => {
      // This test would be run before and after dependency updates
      // to ensure performance hasn't degraded
      
      const benchmarks = {
        health: [],
        api: [],
        auth: []
      };
      
      // Run multiple iterations
      for (let i = 0; i < 20; i++) {
        let start, duration;
        
        // Health endpoint
        start = performance.now();
        await request(app).get('/health');
        duration = performance.now() - start;
        benchmarks.health.push(duration);
        
        // API endpoint
        start = performance.now();
        await request(app).get('/api/version');
        duration = performance.now() - start;
        benchmarks.api.push(duration);
        
        // Auth endpoint
        start = performance.now();
        await request(app)
          .post('/api/auth/login')
          .send({ username: 'admin', password: 'password123' });
        duration = performance.now() - start;
        benchmarks.auth.push(duration);
      }
      
      // Calculate averages
      const results = {};
      for (const [endpoint, times] of Object.entries(benchmarks)) {
        const avg = times.reduce((a, b) => a + b, 0) / times.length;
        const min = Math.min(...times);
        const max = Math.max(...times);
        
        results[endpoint] = { avg, min, max };
        
        console.log(`${endpoint} benchmark:`);
        console.log(`  Avg: ${avg.toFixed(2)}ms`);
        console.log(`  Min: ${min.toFixed(2)}ms`);
        console.log(`  Max: ${max.toFixed(2)}ms`);
      }
      
      // These values would be compared against baseline measurements
      // In a real scenario, you'd store these and compare after updates
      expect(results.health.avg).toBeLessThan(PERFORMANCE_THRESHOLDS.health);
      expect(results.api.avg).toBeLessThan(PERFORMANCE_THRESHOLDS.api);
      expect(results.auth.avg).toBeLessThan(PERFORMANCE_THRESHOLDS.auth);
    });
  });
});

// Export performance testing utilities
module.exports = {
  runPerformanceBenchmark: async () => {
    console.log('Running performance benchmark...');
    
    const endpoints = [
      { path: '/health', method: 'GET', threshold: 100 },
      { path: '/api/version', method: 'GET', threshold: 500 }
    ];
    
    const results = {};
    
    for (const endpoint of endpoints) {
      const times = [];
      
      for (let i = 0; i < 10; i++) {
        const start = performance.now();
        await request(app)[endpoint.method.toLowerCase()](endpoint.path);
        const duration = performance.now() - start;
        times.push(duration);
      }
      
      const avg = times.reduce((a, b) => a + b, 0) / times.length;
      results[endpoint.path] = {
        avg: avg.toFixed(2),
        pass: avg < endpoint.threshold
      };
    }
    
    console.log('Benchmark Results:', results);
    return results;
  }
};
