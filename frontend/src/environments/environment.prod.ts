export const environment = {
  production: true,
  apiUrl: '/api',
  wsHost: window.location.hostname,
  wsPort: 443,
  wsKey: 'securicam-app-key',
  wsScheme: 'https',
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
  ]
};
