/**
 * Sample Express Application
 * Demonstrates various dependencies that Dependabot will monitor
 */

const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const compression = require('compression');
const bodyParser = require('body-parser');
const winston = require('winston');
const dotenv = require('dotenv');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcrypt');
const { v4: uuidv4 } = require('uuid');
const _ = require('lodash');
const moment = require('moment');
const axios = require('axios');

// Load environment variables
dotenv.config();

// Initialize Express app
const app = express();
const PORT = process.env.PORT || 3000;

// Logger configuration
const logger = winston.createLogger({
  level: 'info',
  format: winston.format.json(),
  defaultMeta: { service: 'dependabot-demo' },
  transports: [
    new winston.transports.File({ filename: 'error.log', level: 'error' }),
    new winston.transports.File({ filename: 'combined.log' }),
    new winston.transports.Console({
      format: winston.format.simple(),
    }),
  ],
});

// Middleware
app.use(helmet());
app.use(cors());
app.use(compression());
app.use(bodyParser.json());
app.use(bodyParser.urlencoded({ extended: true }));

// Request logging middleware
app.use((req, res, next) => {
  const requestId = uuidv4();
  req.requestId = requestId;
  logger.info('Request received', {
    requestId,
    method: req.method,
    url: req.url,
    timestamp: moment().format(),
  });
  next();
});

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({
    status: 'healthy',
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
    version: process.env.npm_package_version,
  });
});

// Sample API endpoints
app.get('/api/version', (req, res) => {
  res.json({
    version: process.env.npm_package_version,
    dependencies: require('./package.json').dependencies,
    nodeVersion: process.version,
  });
});

app.post('/api/auth/login', async (req, res) => {
  const { username, password } = req.body;
  
  // Sample authentication logic
  if (!username || !password) {
    return res.status(400).json({ error: 'Username and password required' });
  }
  
  // Mock user verification
  const mockHashedPassword = await bcrypt.hash('password123', 10);
  const isValid = await bcrypt.compare(password, mockHashedPassword);
  
  if (username === 'admin' && isValid) {
    const token = jwt.sign(
      { userId: uuidv4(), username },
      process.env.JWT_SECRET || 'secret',
      { expiresIn: '1h' }
    );
    
    res.json({
      token,
      expiresIn: 3600,
    });
  } else {
    res.status(401).json({ error: 'Invalid credentials' });
  }
});

app.get('/api/data', async (req, res) => {
  try {
    // Sample external API call
    const response = await axios.get('https://jsonplaceholder.typicode.com/posts/1');
    
    // Process data with lodash
    const processed = _.pick(response.data, ['title', 'body']);
    processed.timestamp = moment().format('YYYY-MM-DD HH:mm:ss');
    processed.id = uuidv4();
    
    res.json(processed);
  } catch (error) {
    logger.error('Error fetching data', { error: error.message });
    res.status(500).json({ error: 'Internal server error' });
  }
});

// Sample utility functions using dependencies
const utilities = {
  generateId: () => uuidv4(),
  
  formatDate: (date) => moment(date).format('YYYY-MM-DD'),
  
  sanitizeData: (data) => _.omit(data, ['password', 'secret', 'token']),
  
  hashPassword: async (password) => {
    const saltRounds = 10;
    return await bcrypt.hash(password, saltRounds);
  },
  
  verifyPassword: async (password, hash) => {
    return await bcrypt.compare(password, hash);
  },
  
  generateToken: (payload) => {
    return jwt.sign(payload, process.env.JWT_SECRET || 'secret', {
      expiresIn: '24h',
    });
  },
  
  verifyToken: (token) => {
    try {
      return jwt.verify(token, process.env.JWT_SECRET || 'secret');
    } catch (error) {
      return null;
    }
  },
  
  makeHttpRequest: async (url, options = {}) => {
    try {
      const response = await axios({
        url,
        ...options,
      });
      return response.data;
    } catch (error) {
      logger.error('HTTP request failed', { url, error: error.message });
      throw error;
    }
  },
};

// Error handling middleware
app.use((err, req, res, next) => {
  logger.error('Unhandled error', {
    requestId: req.requestId,
    error: err.message,
    stack: err.stack,
  });
  
  res.status(500).json({
    error: 'Internal server error',
    requestId: req.requestId,
  });
});

// 404 handler
app.use((req, res) => {
  res.status(404).json({
    error: 'Not found',
    path: req.url,
  });
});

// Start server
if (require.main === module) {
  app.listen(PORT, () => {
    logger.info(`Server started on port ${PORT}`);
    logger.info(`Environment: ${process.env.NODE_ENV || 'development'}`);
    logger.info(`Health check available at http://localhost:${PORT}/health`);
  });
}

module.exports = { app, utilities, logger };
