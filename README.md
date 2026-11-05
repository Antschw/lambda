# Transloco demo

Application Angular minimale qui montre comment mettre en place
[Transloco](https://jsverse.gitbook.io/transloco) (i18n) avec les bonnes pratiques actuelles :
deux langues (anglais, français), un sélecteur de langue et des tests.

- Angular 22 (standalone, zoneless, signals)
- Transloco 8.4
- TypeScript 6

**👉 Le tutoriel pas à pas est dans [TUTORIEL-TRANSLOCO.md](TUTORIEL-TRANSLOCO.md).**

## Lancer le projet

Prérequis : Node.js `^22.22.3`, `^24.15.0` ou `>=26`.

```bash
npm install
```

```bash
npm start
```

L'application est servie sur <http://localhost:4200/>.

```bash
npm test
```

```bash
npm run build
```

## Structure

```text
public/i18n/
├── en.json                    # traductions anglaises
└── fr.json                    # traductions françaises
src/app/
├── i18n/
│   ├── transloco-config.ts    # langues + configuration partagée (app et tests)
│   ├── transloco-loader.ts    # chargement HTTP des fichiers de traduction
│   └── transloco-testing.ts   # module Transloco pour les tests unitaires
├── language-switcher/         # composant de changement de langue
├── app.config.ts              # provideTransloco(...)
└── app.ts / app.html          # page traduite + synchronisation de <html lang>
```
