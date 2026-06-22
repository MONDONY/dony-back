import http from 'k6/http';
export function getToken() {
  // Chemin sûr par défaut : token ID pré-minté (mint contre staging/dev, ex.
  // via /dev/token). Les apps en auth téléphone n'ont pas d'user email/password.
  if (__ENV.K6_ID_TOKEN) return __ENV.K6_ID_TOKEN;

  // Fallback : login email/password contre l'API Firebase LIVE (identitytoolkit).
  // Ce chemin IGNORE les gardes BASE_URL et mint un VRAI token pour le projet
  // Firebase de la clé fournie → opt-in explicite requis pour éviter de cibler
  // un projet de production par accident.
  if (__ENV.K6_ALLOW_FIREBASE_LOGIN !== 'true') {
    throw new Error(
      'Aucun K6_ID_TOKEN fourni. Pour utiliser le login Firebase live, ' +
        'définir K6_ALLOW_FIREBASE_LOGIN=true ET une FIREBASE_API_KEY de projet ' +
        'STAGING/DEV (jamais prod).',
    );
  }
  const key = __ENV.FIREBASE_API_KEY;
  const res = http.post(
    `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${key}`,
    JSON.stringify({ email: __ENV.K6_TEST_EMAIL, password: __ENV.K6_TEST_PASSWORD, returnSecureToken: true }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (res.status !== 200) throw new Error(`auth failed: ${res.status} ${res.body}`);
  return JSON.parse(res.body).idToken;
}
