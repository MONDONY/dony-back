# Cucumber E2E — état, fonctionnement et mode d'emploi pour étendre la couverture

> Branche : `fix/cucumber-e2e`. Tout ce qui suit est dans cette branche, **non commité**.

## 1. État actuel

| Indicateur | Valeur |
|---|---|
| Scénarios cucumber verts | **99 / 99** |
| Fichiers `.feature` | 26 (≈14 nouveaux domaines) |
| Couverture **cucumber seul** (bundle gaté du pom) | **~48,6 %** instructions (départ : 24 %) |
| Services externes déjà refactorés (Pattern B) | `PaymentService` (6→31 %), `CashCommissionService` (4→8 %) ; `GoogleAddressService` 1→55 % (Pattern A) |
| Couverture **suite complète** (unit + intégration + E2E) | passe le gate jacoco (≥75 %/70 %), ~85-89 % |
| Build entier | `./mvnw verify` → vert (1600+ tests + cucumber) |
| Gating | réparé : un scénario rouge **fait échouer le build** |

### Plafond structurel
~25 % du code métier appelle Stripe/Google. Deux techniques (ci-dessous) rendent ce code
atteignable en E2E. **Sans elles**, le plafond E2E est ~75 %. Atteindre 80 % cucumber-seul
suppose d'appliquer ces techniques à **tous** les services externes **et** d'écrire des
scénarios pour quasiment chaque branche — effort multi-jours.

## 2. Comment ça marche

### Lancement
```bash
./mvnw test -Dtest=CucumberE2ETest           # scénarios seuls (~1-2 min)
./mvnw verify                                # build complet + gate jacoco
```

### Pièces clés
- `CucumberE2ETest` — **pas un `@Suite`** (Surefire ne compte pas les tests sous le Suite
  engine → un échec passait inaperçu). C'est un `@Test` qui pilote le moteur Cucumber via le
  `Launcher` JUnit et **assert 0 échec** → le build casse si un scénario casse.
- `CucumberSpringContext` — `@SpringBootTest(RANDOM_PORT)`, profil `e2e`, Postgres embarqué
  (zonky), `@MockBean(name="placesRestTemplate")`.
- `E2EMockConfig` — beans mockés du profil e2e : `FirestoreService`, `StorageService`,
  **`StripeGateway`** (voir §3).
- `CucumberHooks` — avant chaque scénario : configure RestAssured, `TRUNCATE … CASCADE`
  (isolation), re-stub les mocks (Storage, `placesRestTemplate`).
- `application-e2e.yml` — `dony.kyc.enforce=false`, `dony.stripe.enforce=false`,
  `spring.main.allow-bean-definition-overriding=true`, `app.places.*`.
- **Ponts SQL** (`TestDataSteps`) — l'E2E n'a pas de vrai KYC/Stripe ; on force les états via
  SQL : `le KYC de X est vérifié`, `le compte X est un compte PRO`,
  `le compte Stripe du voyageur X est opérationnel`, `l'offre X est marquée comme livrée`, etc.

## 3. Rendre du code « externe » atteignable en E2E — 2 patterns

### Pattern A — le service injecte déjà son client HTTP (ex. `GoogleAddressService`)
Aucun changement de prod. Le service reçoit `@Qualifier("placesRestTemplate") RestTemplate` :
1. `@MockBean(name = "placesRestTemplate") RestTemplate` sur `CucumberSpringContext`
   (un `@Bean` override perd contre la config scannée — `@MockBean` gagne de façon fiable).
2. Stub des réponses dans `CucumberHooks.resetMocks()` (POST=autocomplete, GET=details,
   `getForObject`=geocode), aux **formes JSON exactes** lues par le parseur.
3. Scénarios → `GoogleAddressService` 1 % → 55 %.

### Pattern B — le service appelle les **statics** du SDK (ex. `PaymentService` → Stripe)
On introduit une **couture** injectable :
1. Interface `StripeGateway` + impl `StripeGatewayImpl` (`@Component`) **passe-plat** :
   `createPaymentIntent` → `PaymentIntent.create(params)`, etc. **Comportement prod inchangé.**
2. `PaymentService` reçoit `StripeGateway` au constructeur ; chaque `PaymentIntent.create(...)`
   devient `stripeGateway.createPaymentIntent(...)`.
3. Tests unitaires existants : ils `mockStatic(PaymentIntent.class)`. Comme l'impl appelle le
   static, on passe `new StripeGatewayImpl()` au constructeur → **les mockStatic interceptent
   toujours**, 0 réécriture (juste l'argument ajouté aux ~9 sites de construction).
4. Profil e2e : `@Bean @Primary StripeGateway` mocké (renvoie des `PaymentIntent`/`Account`
   Mockito avec `getId`/`getClientSecret`/capability `card_payments`="active").
5. Scénarios → `PaymentService` 6 % → 24 % (escrow + onboarding Connect), **66 tests unitaires
   paiement toujours verts**.

## 4. Étendre aux services restants (pour viser plus haut)

Par ordre de rendement, appliquer **Pattern B** :

| Service | Instr. | APIs Stripe à couvrir dans le gateway |
|---|---|---|
| `CashCommissionService` | 1502 | `SetupIntent` (create/retrieve), `PaymentMethod.retrieve`, `Customer.create`, `Refund.create`, `PaymentIntent` + `RequestOptions` |
| `BidCheckoutService` | 512 | (vérifier — délègue probablement à `PaymentService`) |
| `MobileMoneyPaymentService` | 477 | passerelle Wave/Orange — voir si un client est injecté (Pattern A) |
| `PaymentService` (reste) | — | flux release sur livraison, force-release admin, escrow négociation, webhooks |

Pour chacun : (1) étendre/créer le gateway, (2) refactorer les call-sites, (3) ajouter
l'argument aux constructeurs de test, (4) bean mocké e2e, (5) scénarios happy + erreurs.

### Augmenter vite sans refactor
Beaucoup de branches **déjà atteignables** ne sont pas couvertes (validations, cas d'erreur).
Ajouter des scénarios `@error-case` sur les services déjà à >50 % (announcements, bids,
tracking, requests, auth) rapporte sans toucher au code de prod.

## 5. Règles à respecter
- **Aucun commit** demandé sur cette session.
- `StripeGatewayImpl` est un passe-plat strict : ne pas y mettre de logique métier.
- Garder `dony.*.enforce=false` **uniquement** en profil e2e/test (jamais en prod).
- Après tout changement : `./mvnw verify` doit rester vert (le gating casse sinon).
