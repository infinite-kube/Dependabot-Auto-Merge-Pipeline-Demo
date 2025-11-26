"""
Unit tests for the Flask application
"""

import json
import pytest
import jwt
import bcrypt
from datetime import datetime, timedelta
from unittest.mock import Mock, patch, MagicMock
import sys
import os

# Add src directory to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../../src')))

from app import app, DataProcessor


@pytest.fixture
def client():
    """Create a test client for the Flask app"""
    app.config['TESTING'] = True
    with app.test_client() as client:
        yield client


@pytest.fixture
def auth_headers():
    """Generate valid authentication headers"""
    payload = {
        'user_id': 'test-user-123',
        'username': 'testuser',
        'exp': datetime.utcnow() + timedelta(hours=1)
    }
    token = jwt.encode(payload, app.config['SECRET_KEY'], algorithm='HS256')
    return {'Authorization': f'Bearer {token}'}


class TestHealthEndpoint:
    def test_health_check(self, client):
        """Test health check endpoint returns correct status"""
        response = client.get('/health')
        data = json.loads(response.data)
        
        assert response.status_code == 200
        assert data['status'] == 'healthy'
        assert 'timestamp' in data
        assert 'services' in data
        assert 'redis' in data['services']
        assert 'postgresql' in data['services']
        assert 'mongodb' in data['services']
    
    def test_health_check_content_type(self, client):
        """Test health check returns JSON content type"""
        response = client.get('/health')
        assert response.content_type == 'application/json'


class TestVersionEndpoint:
    def test_get_version(self, client):
        """Test version endpoint returns application info"""
        response = client.get('/api/version')
        data = json.loads(response.data)
        
        assert response.status_code == 200
        assert data['version'] == '1.0.0'
        assert 'python_version' in data
        assert 'dependencies' in data
        assert isinstance(data['dependencies'], list)


class TestAuthEndpoints:
    def test_register_missing_credentials(self, client):
        """Test registration with missing credentials"""
        response = client.post('/api/auth/register',
                              json={})
        data = json.loads(response.data)
        
        assert response.status_code == 400
        assert data['error'] == 'Username and password required'
    
    def test_register_success(self, client):
        """Test successful user registration"""
        with patch('app.redis_client') as mock_redis:
            mock_redis.hset = MagicMock()
            
            response = client.post('/api/auth/register',
                                  json={'username': 'newuser', 'password': 'pass123'})
            data = json.loads(response.data)
            
            assert response.status_code == 201
            assert data['message'] == 'User registered successfully'
            assert 'user_id' in data
    
    def test_login_missing_credentials(self, client):
        """Test login with missing credentials"""
        response = client.post('/api/auth/login',
                              json={})
        data = json.loads(response.data)
        
        assert response.status_code == 400
        assert data['error'] == 'Username and password required'
    
    def test_login_invalid_credentials(self, client):
        """Test login with invalid credentials"""
        response = client.post('/api/auth/login',
                              json={'username': 'wrong', 'password': 'wrong'})
        data = json.loads(response.data)
        
        assert response.status_code == 401
        assert data['error'] == 'Invalid credentials'
    
    def test_login_success(self, client):
        """Test successful login"""
        response = client.post('/api/auth/login',
                              json={'username': 'admin', 'password': 'password123'})
        data = json.loads(response.data)
        
        assert response.status_code == 200
        assert 'token' in data
        assert data['expires_in'] == 3600
        
        # Verify token is valid
        decoded = jwt.decode(data['token'], app.config['SECRET_KEY'], algorithms=['HS256'])
        assert decoded['username'] == 'admin'


class TestDataAnalysisEndpoint:
    def test_analyze_data_without_auth(self, client):
        """Test data analysis endpoint requires authentication"""
        response = client.post('/api/data/analyze',
                              json={'values': [[1, 2, 3]]})
        data = json.loads(response.data)
        
        assert response.status_code == 401
        assert data['error'] == 'No token provided'
    
    def test_analyze_data_with_invalid_token(self, client):
        """Test data analysis with invalid token"""
        response = client.post('/api/data/analyze',
                              headers={'Authorization': 'Bearer invalid.token.here'},
                              json={'values': [[1, 2, 3]]})
        data = json.loads(response.data)
        
        assert response.status_code == 401
        assert data['error'] == 'Invalid token'
    
    def test_analyze_data_success(self, client, auth_headers):
        """Test successful data analysis"""
        with patch('app.redis_client') as mock_redis:
            mock_redis.setex = MagicMock()
            
            response = client.post('/api/data/analyze',
                                  headers=auth_headers,
                                  json={'values': [[1, 2, 3], [4, 5, 6]]})
            data = json.loads(response.data)
            
            assert response.status_code == 200
            assert 'mean' in data
            assert 'median' in data
            assert 'std' in data
            assert 'min' in data
            assert 'max' in data
            assert 'shape' in data
    
    def test_analyze_data_no_values(self, client, auth_headers):
        """Test data analysis with no values"""
        response = client.post('/api/data/analyze',
                              headers=auth_headers,
                              json={})
        data = json.loads(response.data)
        
        assert response.status_code == 400
        assert data['error'] == 'No data provided'


class TestExternalFetchEndpoint:
    @patch('requests.get')
    def test_fetch_external_data_success(self, mock_get, client):
        """Test successful external data fetch"""
        mock_response = Mock()
        mock_response.json.return_value = {'id': 1, 'title': 'Test'}
        mock_response.status_code = 200
        mock_response.headers = {'Content-Type': 'application/json'}
        mock_get.return_value = mock_response
        
        response = client.get('/api/external/fetch?url=https://api.example.com/data')
        data = json.loads(response.data)
        
        assert response.status_code == 200
        assert data['data'] == {'id': 1, 'title': 'Test'}
        assert data['status_code'] == 200
    
    @patch('requests.get')
    def test_fetch_external_data_failure(self, mock_get, client):
        """Test external data fetch failure"""
        mock_get.side_effect = Exception('Network error')
        
        response = client.get('/api/external/fetch?url=https://api.example.com/data')
        data = json.loads(response.data)
        
        assert response.status_code == 503
        assert data['error'] == 'External request failed'


class TestCryptoEndpoint:
    def test_encrypt_data_no_message(self, client):
        """Test encryption with no message"""
        response = client.post('/api/crypto/encrypt',
                              json={})
        data = json.loads(response.data)
        
        assert response.status_code == 400
        assert data['error'] == 'No message provided'
    
    def test_encrypt_data_success(self, client):
        """Test successful data encryption"""
        response = client.post('/api/crypto/encrypt',
                              json={'message': 'Secret message'})
        data = json.loads(response.data)
        
        assert response.status_code == 200
        assert 'encrypted' in data
        assert 'key' in data
        assert data['encrypted'] != 'Secret message'


class TestErrorHandlers:
    def test_404_error(self, client):
        """Test 404 error handler"""
        response = client.get('/nonexistent/endpoint')
        data = json.loads(response.data)
        
        assert response.status_code == 404
        assert data['error'] == 'Not found'


class TestDataProcessor:
    def test_process_with_pandas(self):
        """Test pandas data processing"""
        data = {'values': [1, 2, 3, 4, 5]}
        result = DataProcessor.process_with_pandas(data)
        
        assert isinstance(result, dict)
        assert 'values' in result
    
    def test_process_with_numpy(self):
        """Test numpy data processing"""
        data = [1, 2, 3, 4, 5]
        result = DataProcessor.process_with_numpy(data)
        
        assert 'mean' in result
        assert 'std' in result
        assert 'variance' in result
        assert result['mean'] == 3.0
    
    @patch('app.redis_client')
    def test_cache_result(self, mock_redis):
        """Test caching result in Redis"""
        mock_redis.setex = MagicMock()
        
        DataProcessor.cache_result('test_key', 'test_value', ttl=60)
        mock_redis.setex.assert_called_once_with('test_key', 60, 'test_value')
    
    @patch('app.redis_client')
    def test_get_cached_result(self, mock_redis):
        """Test getting cached result from Redis"""
        mock_redis.get.return_value = 'cached_value'
        
        result = DataProcessor.get_cached_result('test_key')
        assert result == 'cached_value'
        mock_redis.get.assert_called_once_with('test_key')


class TestSecurityFeatures:
    def test_sql_injection_attempt(self, client):
        """Test SQL injection protection"""
        malicious_input = "'; DROP TABLE users; --"
        response = client.post('/api/auth/login',
                              json={'username': malicious_input, 'password': 'pass'})
        data = json.loads(response.data)
        
        assert response.status_code == 401
        assert data['error'] == 'Invalid credentials'
    
    def test_xss_attempt(self, client):
        """Test XSS protection"""
        xss_payload = '<script>alert("XSS")</script>'
        response = client.post('/api/auth/register',
                              json={'username': xss_payload, 'password': 'pass'})
        
        # Should handle safely without executing script
        assert response.status_code in [201, 400]
        assert '<script>' not in response.data.decode()
    
    def test_password_hashing(self):
        """Test passwords are hashed properly"""
        password = 'testPassword123'
        salt = bcrypt.gensalt()
        hashed = bcrypt.hashpw(password.encode('utf-8'), salt)
        
        assert password not in hashed.decode('utf-8')
        assert bcrypt.checkpw(password.encode('utf-8'), hashed)
    
    def test_jwt_expiration(self):
        """Test JWT token expiration"""
        payload = {
            'user_id': 'test-user',
            'exp': datetime.utcnow() - timedelta(hours=1)  # Expired
        }
        token = jwt.encode(payload, app.config['SECRET_KEY'], algorithm='HS256')
        
        with pytest.raises(jwt.ExpiredSignatureError):
            jwt.decode(token, app.config['SECRET_KEY'], algorithms=['HS256'])


class TestRateLimiting:
    """Test rate limiting functionality (if implemented)"""
    
    def test_rate_limit_not_exceeded(self, client):
        """Test normal request rate"""
        for i in range(5):
            response = client.get('/health')
            assert response.status_code == 200
    
    @pytest.mark.skip(reason="Rate limiting not implemented in demo")
    def test_rate_limit_exceeded(self, client):
        """Test rate limit exceeded"""
        for i in range(100):
            response = client.get('/health')
        
        # Eventually should get rate limited
        assert response.status_code == 429


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
