import http from 'k6/http';
export function getToken() {
  const key = __ENV.FIREBASE_API_KEY;
  const res = http.post(
    `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${key}`,
    JSON.stringify({ email: __ENV.K6_TEST_EMAIL, password: __ENV.K6_TEST_PASSWORD, returnSecureToken: true }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (res.status !== 200) throw new Error(`auth failed: ${res.status} ${res.body}`);
  return JSON.parse(res.body).idToken;
}
