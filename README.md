# Transloco demo — langue dans l'URL

Application Angular qui montre comment faire porter la langue par l'URL (`/en/…`, `/fr/…`) avec
[Transloco](https://jsverse.gitbook.io/transloco) : routes préfixées par la langue, redirection
selon la langue du navigateur, sélecteur de langue qui garde la page courante, et tests.

- Angular 21 LTS (standalone, zoneless, signals, router)
- Transloco 8.4
- TypeScript 5.9

**Tutoriels :**

1. [TUTORIEL-TRANSLOCO.md](TUTORIEL-TRANSLOCO.md) : mise en place de Transloco (base, branche
   `example/transloco`).
2. **[TUTORIEL-TRANSLOCO-URL.md](TUTORIEL-TRANSLOCO-URL.md) : la langue dans l'URL (cette branche).**

## Lancer le projet

Prérequis : Node.js `^20.19.0`, `^22.12.0` ou `>=24`.

```bash
npm install
```

```bash
npm start
```

L'application est servie sur <http://localhost:4200/>, qui redirige vers `/en` ou `/fr` selon la
langue du navigateur.

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
│   ├── i18n.config.ts         # langues, isLang(), configuration Transloco
│   ├── lang-routing.ts        # guard, resolver et redirection liés à la langue
│   ├── transloco-loader.ts    # chargement HTTP des fichiers de traduction
│   └── transloco-testing.ts   # module Transloco pour les tests unitaires
├── language-switcher/         # liens vers la page courante dans chaque langue
├── pages/
│   ├── home/                  # /:lang
│   └── about/                 # /:lang/about
├── app.routes.ts              # routes préfixées par la langue
├── app.config.ts              # provideRouter(...), provideTransloco(...)
└── app.ts / app.html          # en-tête, <router-outlet>, synchronisation de <html lang>
```
