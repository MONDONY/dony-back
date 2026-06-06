# language: fr
@regression @admin
Fonctionnalité: Gestion des codes promo (admin)
  En tant qu'administrateur
  Je veux gérer les codes promotionnels
  Afin d'animer la plateforme avec des réductions

  @happy-path
  Scénario: Cycle de vie complet d'un code promo
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "admin-001" et le téléphone "+33699400001"
    Etant donné l'utilisateur "admin-001" est authentifié en tant qu'ADMIN
    Quand je crée un code promo "SUMMER50" sauvegardé sous "promo-1"
    Alors la réponse HTTP est 201
    Et le champ "code" de la réponse vaut "SUMMER50"
    Et le champ "status" de la réponse vaut "ACTIVE"
    Quand je consulte les codes promo
    Alors la réponse HTTP est 200
    Et la réponse est une liste de 1 élément
    Quand je consulte le code promo "promo-1"
    Alors la réponse HTTP est 200
    Et le champ "code" de la réponse vaut "SUMMER50"
    Quand je mets le code promo "promo-1" en pause
    Alors la réponse HTTP est 200
    Et le champ "status" de la réponse vaut "DISABLED"
    Quand je supprime le code promo "promo-1"
    Alors la réponse HTTP est 204

  @error-case
  Scénario: Un expéditeur ne peut pas créer de code promo
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "admin-002" et le téléphone "+33699400002"
    Etant donné l'utilisateur "admin-002" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée un code promo "WINTER20" sauvegardé sous "promo-2"
    Alors la réponse HTTP est 403
