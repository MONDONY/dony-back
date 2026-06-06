# language: fr
@regression @address
Fonctionnalité: Recherche d'adresses (Google Places)
  En tant qu'utilisateur
  Je veux rechercher, détailler et géocoder des adresses
  Afin de saisir précisément mes points de retrait et de livraison

  @happy-path
  Scénario: Autocomplétion, détails et géocodage inverse d'une adresse
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "addr-google-001" et le téléphone "+33666000001"
    Etant donné l'utilisateur "addr-google-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je recherche des adresses pour "Dakar"
    Alors la réponse HTTP est 200
    Et la réponse est une liste non vide
    Quand je consulte les détails du lieu "ChIJtest123"
    Alors la réponse HTTP est 200
    Et le champ "city" de la réponse vaut "Dakar"
    Et le champ "country" de la réponse vaut "SN"
    Quand je géocode des coordonnées valides
    Alors la réponse HTTP est 200
