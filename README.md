# lambda — exemples

Dépôt de projets de démonstration. Chaque exemple vit sur sa propre branche, créée à partir de
`main`, et contient un tutoriel en Markdown.

## Internationalisation (i18n) d'une application Angular 21 LTS

Trois façons de traduire une application Angular, de la plus simple à la plus « site public » :

| Branche                       | Approche                                                       | Tutoriel                                              |
| ----------------------------- | -------------------------------------------------------------- | ----------------------------------------------------- |
| `example/transloco`           | Transloco, langue choisie avec un sélecteur                    | `TUTORIEL-TRANSLOCO.md`                               |
| `example/transloco-url`       | Transloco, langue dans l'URL (`/fr/…`)                         | `TUTORIEL-TRANSLOCO-URL.md` (fait suite au précédent) |
| `example/i18n-angular-native` | i18n native d'Angular (`@angular/localize`), langue dans l'URL | `TUTORIEL-I18N-ANGULAR.md`                            |

### Comparatif

|                                              | Transloco + sélecteur            | Transloco + URL                   | i18n native Angular                         |
| -------------------------------------------- | -------------------------------- | --------------------------------- | ------------------------------------------- |
| Langue dans l'URL (partage, historique, SEO) | ❌                               | ✅                                | ✅ (par construction)                       |
| Changement de langue sans recharger la page  | ✅                               | ✅                                | ❌ (on charge l'autre build)                |
| Nombre de builds à déployer                  | 1                                | 1                                 | 1 par langue                                |
| Coût à l'exécution                           | Chargement d'un JSON par langue  | Idem                              | Aucun (texte compilé dans le code)          |
| Traduction manquante détectée                | À l'exécution (avertissement)    | Idem                              | Au build (erreur)                           |
| Pluriels, genres (ICU)                       | Plugin `transloco-messageformat` | Idem                              | Inclus                                      |
| Dates, nombres, devises dans la bonne langue | Plugin `transloco-locale`        | Idem                              | Automatique (`LOCALE_ID`)                   |
| Format des traductions                       | JSON (clé → texte)               | JSON                              | XLIFF (standard des traducteurs), JSON, ARB |
| Modifier une traduction sans rebuild         | ✅ (fichier JSON servi)          | ✅                                | ❌                                          |
| Dépendance                                   | Bibliothèque communautaire       | Idem                              | Officielle, versionnée avec Angular         |
| Complexité                                   | Faible                           | Moyenne (routes, guard, resolver) | Moyenne (config build et serveur)           |

### Transloco + sélecteur de langue (`example/transloco`)

**Pour**

- Le plus simple à mettre en place et à expliquer.
- Changement de langue instantané, sans rechargement.
- Un seul build ; fichiers JSON faciles à lire et à modifier.
- Traductions chargées à l'exécution : elles peuvent venir d'une API ou d'un CMS, et être
  corrigées sans redéployer l'application.

**Contre**

- La langue n'est pas dans l'URL : un lien partagé s'ouvre dans la langue par défaut, et une
  seule langue est indexée par les moteurs de recherche.
- Dépendance à une bibliothèque tierce, maintenue par la communauté.
- Le template contient des clés (`t('home.title')`) et non le texte : moins lisible.
- Une clé manquante ou mal orthographiée n'est détectée qu'à l'exécution (ou avec l'outil
  `transloco-keys-manager`).
- Pluriels et formats de dates demandent des plugins supplémentaires.

**Idéal pour** : application métier derrière un login (back-office, SaaS, outil interne).

### Transloco + langue dans l'URL (`example/transloco-url`)

**Pour**

- Tous les avantages de la version précédente, y compris le changement sans rechargement.
- URL partageables, historique du navigateur cohérent, une URL indexable par langue.
- Toujours un seul build et un seul déploiement.

**Contre**

- Plus de code : route `:lang`, guard, resolver, redirection, sélecteur qui reconstruit l'URL.
- Pour un vrai référencement, il faut en plus du rendu serveur ou du pré-rendu (SSR) et des
  balises `hreflang`.
- Mêmes limites que Transloco pour les clés, pluriels et formats.

**Idéal pour** : site public ou application partagée par liens, qui a besoin de changer de langue
sans rechargement ou de traductions chargées dynamiquement.

### i18n native d'Angular (`example/i18n-angular-native`)

**Pour**

- Solution officielle, sans dépendance tierce, mise à jour avec Angular.
- Aucun coût à l'exécution : chaque build contient directement ses textes.
- Le texte source reste lisible dans le template (`<h1 i18n="@@home.title">Angular i18n demo</h1>`).
- Traductions manquantes détectées **au build** (`i18nMissingTranslation: "error"`).
- Pluriels et genres (ICU), dates, nombres et devises gérés nativement.
- XLIFF, le format standard des outils et agences de traduction.
- Langue dans l'URL, `<html lang>` et `<base href>` réglés automatiquement.

**Contre**

- Changer de langue recharge la page (on passe à un autre build).
- Un build par langue : temps de build et taille du déploiement multipliés.
- Le serveur de développement ne sert qu'une langue à la fois.
- Le serveur de production doit être configuré (redirection selon la langue, repli par langue).
- Toute modification de traduction impose un rebuild et un redéploiement.
- Pas de fusion automatique des fichiers XLIFF quand les textes évoluent.
- Pas de clé de traduction calculée à l'exécution.

**Idéal pour** : site public ou de contenu, où le référencement, les performances et le travail
avec des traducteurs comptent plus que le changement de langue à la volée.
