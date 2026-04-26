# language: fr
@regression @announcements
Fonctionnalité: Recherche d'annonces de voyage
  En tant qu'expéditeur
  Je veux rechercher des annonces disponibles
  Afin de trouver un voyageur pour transporter mon colis

  @happy-path
  Scénario: Recherche de toutes les annonces disponibles
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-001" et le téléphone "+33644000001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-paris-dakar"
    Et un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-002" et le téléphone "+33644000002"
    Et il existe une annonce de "Lyon" à "Abidjan" avec 15 kg disponibles à 6.0 €/kg sauvegardée sous "annonce-lyon-abidjan"
    Quand je recherche toutes les annonces
    Alors la réponse HTTP est 200
    Et la réponse contient au moins 2 annonce(s)

  @happy-path
  Scénario: Filtrage par ville de départ
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-003" et le téléphone "+33644000003"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-1"
    Et un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-004" et le téléphone "+33644000004"
    Et il existe une annonce de "Lyon" à "Dakar" avec 10 kg disponibles à 7.0 €/kg sauvegardée sous "annonce-2"
    Quand je recherche les annonces au départ de "Paris"
    Alors la réponse HTTP est 200
    Et la réponse contient 1 annonce(s)
    Et toutes les annonces partent de "Paris"

  @happy-path
  Scénario: Filtrage par ville d'arrivée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-005" et le téléphone "+33644000005"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-3"
    Quand je recherche les annonces à destination de "Dakar"
    Alors la réponse HTTP est 200
    Et la réponse contient au moins 1 annonce(s)

  @happy-path
  Scénario: Résultats vides quand aucune annonce ne correspond
    Etant donné l'utilisateur "searcher-empty-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je recherche les annonces au départ de "Ville-Inexistante"
    Alors la réponse HTTP est 200
    Et la réponse contient 0 annonce(s)
