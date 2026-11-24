import { hasConfiguredKey, verifyAnyCredential } from '../security.js';
import { app } from '../context.js';

// Authentication & Handshake status endpoint (Never leaks secret value)
app.get('/api/auth/status', (req, res) => {
  const authHeader = (req.headers.authorization || req.headers['x-api-key'] || req.headers['x-jarvis-token']) as string;
  const isValid = verifyAnyCredential(authHeader);
  res.json({
    status: 'ok',
    authActive: true,
    entropy: '256-bit (32 bytes crypto-grade)',
    authenticated: isValid,
    source: hasConfiguredKey() ? 'environment_variable' : 'secure_runtime_vault',
    serverTime: Date.now(),
  });
});


// Android Native App Connection Handshake & Verification Endpoint
app.post('/api/android/verify-connection', (req, res) => {
  const authHeader = (req.headers.authorization || req.headers['x-api-key'] || req.headers['x-jarvis-token'] || req.body?.apiKey) as string;
  // A paired phone has its own credential, verified by the registered device verifier.
  const isValid = verifyAnyCredential(authHeader);

  // If a key was provided but is invalid, return 401
  if (authHeader && !isValid) {
    return res.status(401).json({
      success: false,
      error: 'Unauthorized',
      message: 'La clé de sécurité OPENJARVIS fournie est invalide.',
    });
  }

  res.json({
    success: true,
    client: 'android_native_bridge',
    status: 'connected',
    serverVersion: 'OpenJarvis 1.0.1 (Production)',
    authenticated: isValid,
    timestamp: Date.now(),
    message: 'Connexion sécurisée établie avec le noyau JARVIS.',
  });
});

