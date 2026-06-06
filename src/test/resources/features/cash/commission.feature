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
