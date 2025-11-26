"""
Sample Flask Application
Demonstrates various Python dependencies that Dependabot will monitor
"""

import os
import logging
from datetime import datetime, timedelta
from functools import wraps

from flask import Flask, jsonify, request, g
from flask import Flask, jsonify, request, g
import redis
import psycopg2
import jwt
import bcrypt
import requests
import pandas as pd
import numpy as np
from dotenv import load_dotenv
from pymongo import MongoClient
from sqlalchemy import create_engine
from cryptography.fernet import Fernet

# Load environment variables
load_dotenv()

# Initialize Flask app
app = Flask(__name__)
app.config['SECRET_KEY'] = os.getenv('SECRET_KEY', 'dev-secret-key')
app.config['DEBUG'] = os.getenv('DEBUG', 'False').lower() == 'true'

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Initialize Redis connection
try:
    redis_client = redis.Redis(
        host=os.getenv('REDIS_HOST', 'localhost'),
        port=int(os.getenv('REDIS_PORT', 6379)),
        db=0,
        decode_responses=True
    )
    redis_client.ping()
    logger.info("Redis connection established")
except Exception as e:
    logger.error(f"Redis connection failed: {e}")
    redis_client = None

# Database connection helper
def get_db_connection():
    """Get PostgreSQL database connection"""
    try:
        conn = psycopg2.connect(
            host=os.getenv('DB_HOST', 'localhost'),
            database=os.getenv('DB_NAME', 'demo'),
            user=os.getenv('DB_USER', 'postgres'),
            password=os.getenv('DB_PASSWORD', 'password')
        )
        return conn
    except Exception as e:
        logger.error(f"Database connection failed: {e}")
        return None

# MongoDB connection
try:
    mongo_client = MongoClient(os.getenv('MONGO_URI', 'mongodb://localhost:27017/'))
    mongo_db = mongo_client['demo_db']
    logger.info("MongoDB connection established")
except Exception as e:
    logger.error(f"MongoDB connection failed: {e}")
    mongo_db = None

# Authentication decorator
def require_auth(f):
    """Decorator to require authentication"""
    @wraps(f)
    def decorated_function(*args, **kwargs):
        token = request.headers.get('Authorization')
        
        if not token:
            return jsonify({'error': 'No token provided'}), 401
        
        try:
            # Remove 'Bearer ' prefix if present
            if token.startswith('Bearer '):
                token = token[7:]
            
            payload = jwt.decode(
                token,
                app.config['SECRET_KEY'],
                algorithms=['HS256']
            )
            g.user_id = payload['user_id']
        except jwt.ExpiredSignatureError:
            return jsonify({'error': 'Token expired'}), 401
        except jwt.InvalidTokenError:
            return jsonify({'error': 'Invalid token'}), 401
        
        return f(*args, **kwargs)
    
    return decorated_function

# Routes
@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint"""
    health_status = {
        'status': 'healthy',
        'timestamp': datetime.utcnow().isoformat(),
        'services': {
            'redis': 'connected' if redis_client and redis_client.ping() else 'disconnected',
            'postgresql': 'connected' if get_db_connection() else 'disconnected',
            'mongodb': 'connected' if mongo_db else 'disconnected'
        }
    }
    return jsonify(health_status)

@app.route('/api/version', methods=['GET'])
def get_version():
    """Get application version and dependencies"""
    import pkg_resources
    
    installed_packages = []
    for dist in pkg_resources.working_set:
        installed_packages.append({
            'name': dist.key,
            'version': dist.version
        })
    
    return jsonify({
        'version': '1.0.0',
        'python_version': os.sys.version,
        'dependencies': installed_packages[:20]  # Limit to first 20
    })

@app.route('/api/auth/register', methods=['POST'])
def register():
    """User registration endpoint"""
    data = request.get_json()
    username = data.get('username')
    password = data.get('password')
    
    if not username or not password:
        return jsonify({'error': 'Username and password required'}), 400
    
    # Hash password
    salt = bcrypt.gensalt()
    hashed_password = bcrypt.hashpw(password.encode('utf-8'), salt)
    
    # Store in database (mock for demo)
    user_id = os.urandom(16).hex()
    
    # Store in Redis cache if available
    if redis_client:
        redis_client.hset(f"user:{user_id}", mapping={
            'username': username,
            'password': hashed_password.decode('utf-8'),
            'created_at': datetime.utcnow().isoformat()
        })
    
    return jsonify({
        'message': 'User registered successfully',
        'user_id': user_id
    }), 201

@app.route('/api/auth/login', methods=['POST'])
def login():
    """User login endpoint"""
    data = request.get_json()
    username = data.get('username')
    password = data.get('password')
    
    if not username or not password:
        return jsonify({'error': 'Username and password required'}), 400
    
    # Mock authentication
    mock_hashed = bcrypt.hashpw(b'password123', bcrypt.gensalt())
    
    if username == 'admin' and bcrypt.checkpw(password.encode('utf-8'), mock_hashed):
        # Generate JWT token
        payload = {
            'user_id': os.urandom(16).hex(),
            'username': username,
            'exp': datetime.utcnow() + timedelta(hours=1)
        }
        
        token = jwt.encode(
            payload,
            app.config['SECRET_KEY'],
            algorithm='HS256'
        )
        
        return jsonify({
            'token': token,
            'expires_in': 3600
        })
    
    return jsonify({'error': 'Invalid credentials'}), 401

@app.route('/api/data/analyze', methods=['POST'])
@require_auth
def analyze_data():
    """Data analysis endpoint using pandas and numpy"""
    data = request.get_json()
    
    if not data or 'values' not in data:
        return jsonify({'error': 'No data provided'}), 400
    
    try:
        # Convert to pandas DataFrame
        df = pd.DataFrame(data['values'])
        
        # Perform some analysis
        analysis_result = {
            'mean': float(np.mean(df.values)),
            'median': float(np.median(df.values)),
            'std': float(np.std(df.values)),
            'min': float(np.min(df.values)),
            'max': float(np.max(df.values)),
            'shape': df.shape,
            'dtypes': df.dtypes.to_dict()
        }
        
        # Cache result in Redis if available
        if redis_client:
            cache_key = f"analysis:{g.user_id}:{datetime.utcnow().timestamp()}"
            redis_client.setex(
                cache_key,
                300,  # 5 minutes TTL
                str(analysis_result)
            )
        
        return jsonify(analysis_result)
    
    except Exception as e:
        logger.error(f"Analysis failed: {e}")
        return jsonify({'error': 'Analysis failed'}), 500

@app.route('/api/external/fetch', methods=['GET'])
def fetch_external_data():
    """Fetch data from external API"""
    url = request.args.get('url', 'https://jsonplaceholder.typicode.com/posts/1')
    
    try:
        response = requests.get(url, timeout=5)
        response.raise_for_status()
        
        return jsonify({
            'data': response.json(),
            'status_code': response.status_code,
            'headers': dict(response.headers)
        })
    
    except requests.RequestException as e:
        logger.error(f"External request failed: {e}")
        return jsonify({'error': 'External request failed'}), 503

@app.route('/api/crypto/encrypt', methods=['POST'])
def encrypt_data():
    """Encrypt data using cryptography library"""
    data = request.get_json()
    
    if not data or 'message' not in data:
        return jsonify({'error': 'No message provided'}), 400
    
    try:
        # Generate a key
        key = Fernet.generate_key()
        f = Fernet(key)
        
        # Encrypt the message
        encrypted = f.encrypt(data['message'].encode())
        
        return jsonify({
            'encrypted': encrypted.decode(),
            'key': key.decode()
        })
    
    except Exception as e:
        logger.error(f"Encryption failed: {e}")
        return jsonify({'error': 'Encryption failed'}), 500

# Error handlers
@app.errorhandler(404)
def not_found(error):
    """404 error handler"""
    return jsonify({'error': 'Not found'}), 404

@app.errorhandler(500)
def internal_error(error):
    """500 error handler"""
    logger.error(f"Internal error: {error}")
    return jsonify({'error': 'Internal server error'}), 500

# Utility functions
class DataProcessor:
    """Sample data processing class using various dependencies"""
    
    @staticmethod
    def process_with_pandas(data):
        """Process data using pandas"""
        df = pd.DataFrame(data)
        return df.describe().to_dict()
    
    @staticmethod
    def process_with_numpy(data):
        """Process data using numpy"""
        arr = np.array(data)
        return {
            'mean': np.mean(arr),
            'std': np.std(arr),
            'variance': np.var(arr)
        }
    
    @staticmethod
    def cache_result(key, value, ttl=300):
        """Cache result in Redis"""
        if redis_client:
            redis_client.setex(key, ttl, str(value))
    
    @staticmethod
    def get_cached_result(key):
        """Get cached result from Redis"""
        if redis_client:
            return redis_client.get(key)
        return None

if __name__ == '__main__':
    app.run(
        host='0.0.0.0',
        port=int(os.getenv('PORT', 5000)),
        debug=app.config['DEBUG']
    )
