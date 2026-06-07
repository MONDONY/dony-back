# language: fr
@regression @catalog
Fonctionnalité: Modèles et récurrences de trajet
  En tant que voyageur
  Je veux enregistrer des modèles de trajet et des récurrences
  Afin de publier rapidement mes annonces régulières

  @happy-path
  Scénario: Cycle de vie complet d'un modèle de trajet
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "tpl-traveler-001" et le téléphone "+33655100001"
    Etant donné l'utilisateur "tpl-traveler-001" est authentifié en tant que VOYAGEUR
    Quand je crée un modèle de trajet "Paris-Dakar hebdo" sauvegardé sous "tpl-1"
    Alors la réponse HTTP est 201
    Quand je consulte mes modèles de trajet
    Alors la réponse HTTP est 200
    Et la réponse est une liste de 1 élément
    Quand je supprime le modèle de trajet "tpl-1"
    Alors la réponse HTTP est 204

  @happy-path
  Scénario: Cycle de vie complet d'une récurrence de trajet
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "rec-traveler-001" et le téléphone "+33655100002"
    Etant donné l'utilisateur "rec-traveler-001" est authentifié en tant que VOYAGEUR
    Quand je crée une récurrence de trajet sauvegardée sous "rec-1"
    Alors la réponse HTTP est 201
    Quand je consulte mes récurrences de trajet
    Alors la réponse HTTP est 200
    Et la réponse est une liste de 1 élément
    Quand je supprime la récurrence de trajet "rec-1"
    Alors la réponse HTTP est 204
