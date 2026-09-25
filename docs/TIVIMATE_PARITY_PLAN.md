# Plan de parité comportementale TiviMate

## Objectif

Reproduire les fonctions et comportements utiles de TiviMate dans TufaraTV, sans copier son code,
ses ressources ou ses mécanismes Premium. Priorité absolue : fluidité Live TV, continuité du flux,
cache persistant et interface française.

## État mesuré au 23 septembre 2026

- TiviMate sur le Chromecast HD : environ 31 ms médian, 4 % de frames lentes sur le parcours test.
- Ancien dashboard Compose : 69–77 ms médian, 68–87 % de frames lentes.
- Premier dashboard RecyclerView natif : 53 ms médian.
- Dashboard natif sans zoom/élévation : 48 ms médian, encore insuffisant.
- Démarrage à chaud/caché : 1,7–2,0 s observé.
- Un seul ExoPlayer est déjà partagé entre preview et plein écran.
- Retour plein écran → preview conserve déjà la connexion et le son.
- L’EPG, les favoris, les récents et la disponibilité fournisseur sont déjà en cache Room.

## Phase 1 — Corriger le dashboard natif

1. Garantir que le rail Live affiche et focalise toujours toutes les catégories; ne réactiver un
   overlay qu’avec un conteneur natif mesuré, jamais avec un parent Compose de largeur zéro.
2. Remplacer la carte géante « Dernières chaînes regardées » par une carte compacte :
   logo 56 dp, nom, programme en cours, barre de progression et prochain programme.
3. Garder des dimensions distinctes :
   - chaîne récente : environ 240 × 96 dp ;
   - continuer à regarder : environ 190 × 125 dp ;
   - film/série : affiche portrait environ 145 × 250 dp.
4. Utiliser le backdrop TMDB pour le hero, jamais une affiche portrait étirée.
5. Conserver un highlight instantané sans zoom, élévation ni relayout.
6. Vérifier clair/sombre, contraste AA, texte français et focus visible.
7. Éviter AndroidView imbriqué dans une grande scène Compose : déplacer aussi le shell/rail principal
   vers une vue native ou une Activity/Fragment dédiée TV.

Critères de passage : médiane ≤ 33 ms, P95 ≤ 50 ms, jank ≤ 8 % sur 20 déplacements D-pad.

## Phase 2 — Migrer Live TV en vues Android TV natives

Architecture cible : un PlayerView permanent + panneaux RecyclerView superposés. Aucun panneau ne
doit redimensionner la vidéo ou le guide.

1. Panneau gauche : sources et groupes.
2. Panneau central : chaînes du groupe, numéro, logo, favori, programme en cours et progression.
3. Panneau droit : programmes de la chaîne sélectionnée.
4. Panneau détail : titre, description, heures, actions regarder/enregistrer/rappel.
5. Navigation continue groupe → chaîne → programme → détail avec gauche/droite.
6. Premier OK : lance le preview sans fermer la liste.
7. Deuxième OK sur la chaîne déjà active : plein écran sans reconnecter le flux.
8. Retour plein écran : même ExoPlayer, même position live, son continu dans le preview.
9. Overlay chaîne et EPG : même minuterie; ils disparaissent ensemble.
10. Haut/bas : zapping instantané; gauche : liste; droite : programmes; chiffres : numéro de chaîne.
11. « Dernière chaîne » accessible en une action.
12. Aucun accès réseau/SQL lourd pendant un déplacement de focus.

Critères de passage : aucun écran noir au changement preview/plein écran, aucune coupure audio au
retour, un seul décodeur, P95 ≤ 50 ms dans les panneaux, mémoire totale ≤ 120 Mo.

## Phase 3 — Guide EPG complet et rapide

1. RecyclerView vertical pour les chaînes et grille horizontale virtualisée pour le temps.
2. Synchroniser le scroll chaîne/programmes sans recomposer tout le guide.
3. Associer l’EPG à toutes les chaînes via l’ordre : override manuel → identifiant fournisseur →
   correspondance normalisée → source EPG gratuite configurée.
4. Charger seulement la fenêtre visible et précharger une petite marge.
5. Index Room sur identifiant EPG + début/fin; aucune requête globale pendant le scroll.
6. Conserver l’EPG plusieurs jours en cache; rafraîchir en arrière-plan selon TTL.
7. Afficher clairement « Guide indisponible » si aucune donnée réelle n’existe; ne jamais inventer.
8. Navigation aujourd’hui/demain/jours suivants, retour immédiat à « Maintenant ».

## Phase 4 — Gestion TiviMate des groupes et chaînes

1. Réordonner les groupes par glisser/déplacer ou actions D-pad.
2. Renommer/restaurer le nom d’un groupe.
3. Masquer/afficher/bloquer un groupe.
4. Grouper ou séparer les playlists.
5. Tri des chaînes : ordre fournisseur, nom, temps de visionnage, ordre manuel.
6. Réordonner manuellement les chaînes et les favoris.
7. Favoris uniquement et chaînes de groupes masqués configurables.
8. Option lecteur externe par groupe ou chaîne.
9. Options chaîne : nom, favori, visibilité, EPG manuel, décalage EPG, décodeur, lecteur externe.

## Phase 5 — Comportements avancés du lecteur

1. Qualités regroupées par chaîne; afficher seulement celles réellement offertes.
2. Changement de qualité avec le même contrôleur et transition minimale.
3. Taille du buffer configurable.
4. Priorité décodeur matériel/logiciel par défaut et par chaîne.
5. Auto frame rate et résolution, passthrough audio, corrections appareil ciblées.
6. Audio, sous-titres, format d’image et pistes mémorisés.
7. PiP sur Home, démarrage sur dernière chaîne, démarrage au boot optionnel.
8. Catch-up/archive quand le fournisseur le permet.
9. Enregistrement, séries d’enregistrements, rappels et conflits de connexion.
10. Multi-écran seulement après validation mémoire/décodeurs du Chromecast.

## Phase 6 — Films et séries natives

1. Migrer les rangées Films/Séries hors Compose vers RecyclerView.
2. N’afficher que les titres réellement disponibles chez le fournisseur.
3. Garder les catégories TMDB, mais exclure « actuellement au cinéma » du dashboard.
4. Favoris visibles et mis en évidence dans listes, détails et dashboard.
5. « Continuer à regarder » persistant avec reprise exacte.
6. Séries : sélecteur de saison en haut, épisode courant automatiquement visible, pas de descente
   interminable jusqu’à la saison suivante.
7. Liste épisodes native avec vignette, synopsis, durée et état lu/en cours.

## Phase 7 — Réglages et télécommande

1. Migrer les écrans de réglages critiques en vues natives compactes.
2. Mappage séparé Guide/Lecteur pour OK, appui long, retour, directions, CH+/-, lecture, avance,
   retour rapide, Info, Guide, couleurs et touches numériques.
3. Réglages d’apparence : clair/sombre, taille texte, transparence, position horloge, délai panneaux.
4. Priorité logos playlist/EPG/dossier, correspondance approximative et purge du cache logos.
5. Sauvegarde/restauration locale de la configuration.
6. Aucun libellé anglais dans l’interface française; ajouter un test de ressources manquantes.

## Phase 8 — Cache et démarrage

1. Importer les chaînes une seule fois; relancer seulement sur changement de fournisseur, version de
   catalogue, expiration explicite ou action manuelle « Actualiser ».
2. Au démarrage, afficher immédiatement Room; synchroniser silencieusement plus tard.
3. Ne jamais bloquer le premier écran sur TMDB ou l’EPG.
4. Cache image disque dimensionné TV; requêtes avec taille exacte; aucun fondu coûteux.
5. Conserver les listes déjà calculées; pas de tri/normalisation répété au focus.
6. Mesurer démarrage froid, chaud, mémoire, CPU, SQL et réseau avant chaque release.

## Matrice de validation obligatoire

- Chromecast HD réel, release minifiée, Night Screen arrêté pour une mesure correcte.
- Parcours identique TiviMate/TufaraTV : 20 touches horizontales, 20 verticales, 10 changements de
  groupe, 10 zappings, preview → plein écran → preview.
- Objectifs : démarrage cache ≤ 2 s; médiane ≤ 33 ms; P95 ≤ 50 ms; jank ≤ 8 %; idle CPU ≈ 0 %;
  mémoire ≤ 120 Mo; aucun chargement réseau lors du focus.
- Vérifier son continu, EPG overlay synchronisé, qualité réelle, favoris, récents et reprise.
- Tester clair et sombre à 1080p et 4K.
- Tests unitaires + build release + installation + test manuel avant remise de l’APK.

## Ordre de reprise automatique

1. Vérifier/restaurer immédiatement le rail des catégories Live.
2. Corriger immédiatement les cartes récentes trop grandes.
3. Finir le dashboard natif jusqu’aux seuils de performance.
4. Migrer Live TV/panneaux/EPG en natif.
5. Ajouter la gestion groupes/chaînes et les commandes télécommande.
6. Migrer Films/Séries et les épisodes.
7. Terminer réglages, cache, tests de langue et packaging final.
