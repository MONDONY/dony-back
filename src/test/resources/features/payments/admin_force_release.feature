# language: fr
@regression @payments @admin
Fonctionnalité: Libération forcée d'escrow par l'administrateur
  En tant qu'administrateur
  Je veux libérer manuellement un escrow bloqué vers le voyageur
  Afin de débloquer les paiements livrés — sans jamais payer un colis annulé

  @error-case @cancelled-guard
  Scénario: Refus de libérer l'escrow d'un colis annulé
    # Un trajet annulé doit être REMBOURSÉ à l'expéditeur, jamais transféré au voyageur.
    Etant donné un colis annulé avec un paiement en escrow de 50.0 € sauvegardé sous "colis-annule"
    Et l'administrateur "admin-adm-001" est authentifié
    Quand je force la libération du paiement du colis "colis-annule"
    Alors la réponse HTTP est 422
    Et le code d'erreur de la réponse est "bid-cancelled"

  @error-case
  Scénario: Refus de libérer un paiement inexistant
    Etant donné l'administrateur "admin-adm-002" est authentifié
    Quand je force la libération du paiement "11111111-1111-1111-1111-111111111111"
    Alors la réponse HTTP est 404
    Et le code d'erreur de la réponse est "payment-not-found"

  @security
  Scénario: Un non-admin ne peut pas forcer la libération d'un paiement
    Etant donné l'utilisateur "send-adm-003" est authentifié en tant qu'EXPÉDITEUR
    Quand je force la libération du paiement "22222222-2222-2222-2222-222222222222"
    Alors la réponse HTTP est 403

  @error-case @refund
  Scénario: Refus de rembourser un paiement inexistant
    Etant donné l'administrateur "admin-adm-005" est authentifié
    Quand je rembourse le paiement "33333333-3333-3333-3333-333333333333"
    Alors la réponse HTTP est 404
    Et le code d'erreur de la réponse est "payment-not-found"

  @security @refund
  Scénario: Un non-admin ne peut pas rembourser un paiement
    Etant donné l'utilisateur "send-adm-006" est authentifié en tant qu'EXPÉDITEUR
    Quand je rembourse le paiement "44444444-4444-4444-4444-444444444444"
    Alors la réponse HTTP est 403
