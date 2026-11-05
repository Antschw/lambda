# Angular i18n demo — i18n native, langue dans l'URL

Application Angular qui montre comment internationaliser une application **sans bibliothèque
tierce**, avec `@angular/localize` : un build par langue servi sous `/en/` et `/fr/`, sélecteur
de langue, titres de pages, dates et pluriels traduits, et tests.

- Angular 21 LTS (standalone, zoneless, signals, router)
- `@angular/localize` 21
- TypeScript 5.9

**👉 Le tutoriel pas à pas est dans [TUTORIEL-I18N-ANGULAR.md](TUTORIEL-I18N-ANGULAR.md).**

## Lancer le projet

Prérequis : Node.js `^20.19.0`, `^22.12.0` ou `>=24`.

```bash
npm install
```

Serveur de développement, une langue à la fois (<http://localhost:4200/>) :

```bash
npm start
```

```bash
npm run start:fr
```

Toutes les langues, comme en production, avec le sélecteur de langue fonctionnel
(<http://localhost:4200/> redirige vers `/en/` ou `/fr/`) :

```bash
npm run preview
```

Extraire les textes à traduire dans `src/locale/messages.xlf` :

```bash
npm run extract-i18n
```

```bash
npm test
```

```bash
npm run build
```

## Structure

```text
src/locale/
├── messages.xlf               # textes source extraits (anglais), généré
└── messages.fr.xlf            # traductions françaises
src/app/
├── i18n/
│   └── locales.ts             # langues construites (synchronisées avec angular.json)
├── language-switcher/         # liens vers la même page dans l'autre build
├── pages/
│   ├── home/                  # /<langue>/
│   └── about/                 # /<langue>/about
├── app.routes.ts              # routes et titres de pages ($localize)
└── app.ts / app.html          # en-tête et <router-outlet>
scripts/serve-localized.mjs    # serveur de prévisualisation du build localisé
angular.json                   # section i18n : langue source, langues et fichiers de traduction
```
