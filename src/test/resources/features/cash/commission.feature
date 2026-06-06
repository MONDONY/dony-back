# language: fr
@regression @cash
Fonctionnalité: Méthode de paiement de commission (cash)
  En tant que voyageur acceptant des paiements en espèces
  Je veux enregistrer une méthode de prélèvement de la commission
  Afin de pouvoir accepter des offres réglées en cash

  @happy-path
  Scénario: Configuration puis consultation de la méthode de commission
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "cash-traveler-001" et le téléphone "+33677200001"
    Etant donné l'utilisateur "cash-traveler-001" est authentifié en tant que VOYAGEUR
    Quand je configure ma méthode de paiement de commission
    Alors la réponse HTTP est 200
    Quand je consulte ma méthode de paiement de commission
    Alors la réponse HTTP est 204

  @critical @idempotence
  Scénario: Rejeu d'un webhook de commission cash — ingéré une seule fois
    # La commission cash réglée par carte génère un payment_intent.succeeded. Son
    # rejeu par Stripe ne doit jamais reprélever la commission : la même garde
    # d'inbox (dédoublonnage par event id) protège le flux cash comme l'escrow.
    Etant donné aucun utilisateur n'est authentifié
    Quand le webhook Stripe de type "payment_intent.succeeded" et d'identifiant "evt_e2e_cash_replay" est reçu
    Alors la réponse HTTP est 200
    Quand le webhook Stripe de type "payment_intent.succeeded" et d'identifiant "evt_e2e_cash_replay" est reçu
    Alors la réponse HTTP est 200
    Et l'inbox Stripe contient 1 évènement
    Et l'évènement Stripe "evt_e2e_cash_replay" a été ingéré
