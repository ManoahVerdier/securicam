export const environment = {
  production: false,
  apiUrl: 'http://localhost:8000/api',
  wsHost: 'localhost',
  wsPort: 8080,
  wsKey: 'securicam-app-key',
  wsScheme: 'http',
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
  ]
};
