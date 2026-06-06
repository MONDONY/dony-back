# language: fr
@regression @catalog
Fonctionnalité: Grille tarifaire du voyageur
  En tant que voyageur
  Je veux gérer une grille de prix par type de colis
  Afin de proposer des tarifs clairs aux expéditeurs

  @happy-path
  Scénario: Cycle de vie complet d'un article de grille tarifaire
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "grid-traveler-001" et le téléphone "+33699500001"
    Etant donné l'utilisateur "grid-traveler-001" est authentifié en tant que VOYAGEUR
    Quand je crée un article de grille tarifaire "Petit colis" à 10.0 € sauvegardé sous "item-1"
    Alors la réponse HTTP est 201
    Quand je consulte ma grille tarifaire
    Alors la réponse HTTP est 200
    Et la réponse est une liste de 1 élément
    Quand je modifie l'article de grille tarifaire "item-1" avec le libellé "Colis moyen"
    Alors la réponse HTTP est 200
    Et le champ "label" de la réponse vaut "Colis moyen"
    Quand je supprime l'article de grille tarifaire "item-1"
    Alors la réponse HTTP est 204
    Quand je consulte ma grille tarifaire
    Alors la réponse est une liste de 0 élément
