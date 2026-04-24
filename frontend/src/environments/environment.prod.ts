// Production environment.
// Placeholders __PUBLIC_HOST__, __TURN_HOST__, __TURN_USER__, __TURN_PASSWORD__
// are substituted at Docker build time by docker/nginx/Dockerfile.prod.
export const environment = {
  production: true,
  apiUrl: 'https://__PUBLIC_HOST__/api',
  wsHost: '__PUBLIC_HOST__',
  wsPort: 443,
  wsKey: 'securicam-app-key',
  wsScheme: 'https',
  iceServers: [
    { urls: 'stun:__TURN_HOST__:3478' },
    {
      urls: [
        'turn:__TURN_HOST__:3478?transport=udp',
        'turn:__TURN_HOST__:3478?transport=tcp'
      ],
      username: '__TURN_USER__',
      credential: '__TURN_PASSWORD__'
    }
  ]
};

