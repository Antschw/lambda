# Internationaliser une application avec l'i18n native d'Angular — tutoriel

Ce tutoriel met en place l'internationalisation **sans bibliothèque tierce**, avec
`@angular/localize`, l'outil officiel d'Angular. La langue est portée par l'URL :

```text
/            → redirection vers /fr/ ou /en/ (langue du navigateur, côté serveur)
/fr/         → accueil en français
/en/about    → page « About » en anglais
```

Le code de ce dépôt est le résultat final : chaque étape renvoie au fichier concerné.

**Versions** : Angular 21.2 (LTS), `@angular/localize` 21.2, TypeScript 5.9.

---

## Sommaire

1. [Le principe : un build par langue](#1-le-principe--un-build-par-langue)
2. [Créer le projet et ajouter `@angular/localize`](#2-créer-le-projet-et-ajouter-angularlocalize)
3. [Marquer les textes à traduire](#3-marquer-les-textes-à-traduire)
4. [Configurer les langues dans `angular.json`](#4-configurer-les-langues-dans-angularjson)
5. [Extraire et traduire les messages](#5-extraire-et-traduire-les-messages)
6. [Construire l'application](#6-construire-lapplication)
7. [Le sélecteur de langue](#7-le-sélecteur-de-langue)
8. [Développer au quotidien](#8-développer-au-quotidien)
9. [Tester](#9-tester)
10. [Déployer](#10-déployer)
11. [Récapitulatif](#11-récapitulatif)

---

## 1. Le principe : un build par langue

Avec Transloco, une seule application charge des fichiers JSON à l'exécution. L'i18n native
fonctionne autrement : les traductions sont **remplacées dans le code au moment du build**.

```text
                      ┌─> dist/i18n-demo/browser/en/   (<html lang="en">, <base href="/en/">)
ng build ─ localize ──┤
                      └─> dist/i18n-demo/browser/fr/   (<html lang="fr">, <base href="/fr/">)
```

Conséquences :

- **Aucun coût à l'exécution** : pas de fichier de traduction à télécharger, pas de recherche de
  clé ; le texte français est écrit en dur dans le JavaScript du build `fr`.
- **La langue est dans l'URL par construction** : chaque build est servi sous son préfixe.
- **Changer de langue, c'est charger l'autre build**, donc recharger la page.
- Le build connaît sa langue : `LOCALE_ID` vaut `'fr'` dans le build français, et les pipes
  `date`, `number`, `currency` formatent en français sans aucune configuration.

## 2. Créer le projet et ajouter `@angular/localize`

```bash
npx @angular/cli@21 new i18n-demo --style=css --ssr=false
```

> Si `npm install` échoue avec `Cannot read properties of null (reading 'edgesOut')`, c'est un bug
> de npm 10.9 : relancez avec `npx npm@11 install`.

```bash
npx ng add @angular/localize
```

Le schematic :

- ajoute `@angular/localize` aux `devDependencies` (les traductions sont appliquées au build) ;
- ajoute le polyfill `@angular/localize/init` dans `angular.json`, qui définit la fonction
  globale `$localize` ;
- ajoute les types `@angular/localize` dans `tsconfig.app.json` et `tsconfig.spec.json` ;
- ajoute `/// <reference types="@angular/localize" />` en tête de `main.ts`. Cette ligne fait
  doublon avec les `types` des `tsconfig` : on l'a retirée.

## 3. Marquer les textes à traduire

### Dans les templates : l'attribut `i18n`

[`src/app/pages/home/home.html`](src/app/pages/home/home.html)

```html
<h1 i18n="Main heading of the home page@@home.title">Angular i18n demo</h1>
<p i18n="@@home.greeting">Hello {{ name }}!</p>
<p i18n="@@home.description">The language is part of the URL: /en/… or /fr/…</p>
<!-- Le format de date suit la langue du build (LOCALE_ID), sans configuration. -->
<p i18n="@@home.today">Today is {{ today | date: 'fullDate' }}.</p>
```

La valeur de `i18n` suit le format `sens|description@@identifiant`, chaque partie étant
facultative :

- **`@@home.title` : un identifiant personnalisé. C'est la bonne pratique principale.** Sans lui,
  Angular calcule un identifiant à partir du texte anglais : corriger une faute dans le texte
  source change l'identifiant et perd la traduction. Des identifiants lisibles (`page.element`)
  rendent aussi les fichiers de traduction compréhensibles.
- `Main heading of the home page` : une description destinée au traducteur (contexte).
- Le texte source (anglais) reste dans le template : la page est lisible sans fichier de traduction.
- `{{ name }}` et `{{ today | date }}` deviennent des **placeholders** : le traducteur déplace le
  bloc, mais ne le modifie pas.

### Les attributs : `i18n-<attribut>`

[`src/app/app.html`](src/app/app.html) (extrait)

```html
<nav aria-label="Main navigation" i18n-aria-label="@@nav.label"></nav>
```

Même syntaxe pour `title`, `alt`, `placeholder`, etc.

### Les pluriels : les messages ICU

[`src/app/pages/about/about.html`](src/app/pages/about/about.html) (extrait)

<!-- prettier-ignore -->
```html
<!-- Sur une seule ligne : un retour à la ligne autour d'un pluriel ICU change le message extrait. -->
<!-- prettier-ignore -->
<p i18n="@@about.localeCount">{localeCount, plural, =1 {One language available.} other {{{ localeCount }} languages available.}}</p>
```

Chaque langue définit ses propres cas (`=0`, `=1`, `one`, `few`, `many`, `other`…) selon ses
règles de pluriel. On peut aussi utiliser `select` pour les genres ou toute autre variante.

> **Piège rencontré :** quand Prettier a coupé cette ligne, l'espace et le retour à la ligne
> autour du pluriel ont fait extraire **deux** messages : un message `about.localeCount` qui
> contient un placeholder, et le pluriel lui-même avec un identifiant généré automatiquement
> (instable). Sur une seule ligne, le pluriel est extrait directement sous l'identifiant
> `about.localeCount`. D'où le commentaire `<!-- prettier-ignore -->`. De façon générale, les
> espaces en début et fin d'élément font partie du message : vérifiez le fichier extrait.

### Dans le code TypeScript : `$localize`

[`src/app/app.routes.ts`](src/app/app.routes.ts)

```ts
export const routes: Routes = [
  {
    path: '',
    title: $localize`:@@home.pageTitle:Home`,
    loadComponent: () => import('./pages/home/home').then((m) => m.Home),
  },
  {
    path: 'about',
    title: $localize`:@@about.pageTitle:About`,
    loadComponent: () => import('./pages/about/about').then((m) => m.About),
  },
  { path: '**', redirectTo: '' },
];
```

- `$localize` est un _tagged template_ : `:@@identifiant:` en tête, puis le texte source.
- Ici, il traduit le **titre de l'onglet** de chaque page, géré par le router (`title`).
- Les routes ne contiennent **pas** la langue : `/about` est relatif au `<base href="/fr/">` du
  build. Les `routerLink` restent donc dans la langue courante sans rien faire.

## 4. Configurer les langues dans `angular.json`

[`angular.json`](angular.json) (extraits)

```json
"i18n": {
  "sourceLocale": "en",
  "locales": {
    "fr": { "translation": "src/locale/messages.fr.xlf" }
  }
},
```

- `sourceLocale` : la langue des textes écrits dans le code. Sans cette option, Angular suppose
  `en-US`.
- `locales` : une entrée par langue traduite. Le préfixe d'URL (`subPath`) vaut par défaut le
  code de la langue : `/fr/`.

Dans les options du build :

```json
"options": {
  "localize": true,
  "i18nMissingTranslation": "error",
  ...
},
"configurations": {
  ...
  "en": { "localize": ["en"] },
  "fr": { "localize": ["fr"] }
}
```

- `"localize": true` : `ng build` produit toutes les langues.
- `"i18nMissingTranslation": "error"` : **le build échoue si une traduction manque**, au lieu
  d'afficher silencieusement le texte anglais (comportement par défaut : simple avertissement).
- Les configurations `en` et `fr` servent au serveur de développement, qui ne sert qu'une langue
  à la fois (voir [§8](#8-développer-au-quotidien)).

Et pour l'extraction :

```json
"extract-i18n": {
  "builder": "@angular/build:extract-i18n",
  "options": { "outputPath": "src/locale" }
}
```

## 5. Extraire et traduire les messages

```bash
npm run extract-i18n
```

Angular parcourt le code et produit [`src/locale/messages.xlf`](src/locale/messages.xlf), au format
XLIFF (standard des outils de traduction). Un extrait :

```xml
<trans-unit id="home.greeting" datatype="html">
  <source>Hello <x id="INTERPOLATION" equiv-text="{{ name }}"/>!</source>
</trans-unit>
```

Pour le français, on copie ce fichier en
[`src/locale/messages.fr.xlf`](src/locale/messages.fr.xlf), on ajoute `target-language="fr"` sur la
balise `<file>`, puis une balise `<target>` dans chaque `<trans-unit>` :

```xml
<trans-unit id="home.greeting" datatype="html">
  <source>Hello <x id="INTERPOLATION" equiv-text="{{ name }}"/>!</source>
  <target>Bonjour <x id="INTERPOLATION" equiv-text="{{ name }}"/> !</target>
</trans-unit>
```

> **Piège vérifié en écrivant cette démo :**
>
> - un `<trans-unit>` **absent** du fichier français fait échouer le build (grâce à
>   `i18nMissingTranslation: "error"`) ;
> - un `<trans-unit>` présent **sans `<target>`** passe sans erreur ni avertissement, et le texte
>   anglais s'affiche dans le build français.
>
> Quand vous copiez de nouveaux messages depuis `messages.xlf`, ajoutez toujours leur `<target>`.

**Quand les textes évoluent** : relancez `npm run extract-i18n`, puis reportez les messages
nouveaux ou modifiés dans `messages.fr.xlf`. Angular ne fusionne pas les fichiers lui-même ; c'est
le rôle d'un outil de traduction (plateforme de traduction, éditeur XLIFF) ou d'un script.

Autres formats possibles avec l'option `format` : `xlf2` (XLIFF 2.0), `json`, `arb`, `xmb`.

## 6. Construire l'application

```bash
npm run build
```

```text
dist/i18n-demo/browser/
├── en/   index.html  <html lang="en">  <base href="/en/">
└── fr/   index.html  <html lang="fr">  <base href="/fr/">
```

Angular règle automatiquement pour chaque build :

- l'attribut `<html lang>` (accessibilité, SEO) ;
- le `<base href>`, donc toutes les URL de l'application (`routerLink`, ressources) ;
- `LOCALE_ID` et les données de la langue : `Today is …` devient
  `Nous sommes le jeudi 5 novembre 2026.` sans aucun code.

## 7. Le sélecteur de langue

### La liste des langues

[`src/app/i18n/locales.ts`](src/app/i18n/locales.ts)

```ts
export const LOCALES = [
  { code: 'en', label: 'English' },
  { code: 'fr', label: 'Français' },
] as const;

export type Locale = (typeof LOCALES)[number]['code'];
```

- Angular n'expose pas à l'exécution la liste des langues construites : on la déclare ici. Un
  test vérifie qu'elle correspond à `angular.json` (voir [§9](#9-tester)).
- Les libellés sont des **autonymes** (« Français », pas « French ») : un utilisateur doit
  reconnaître sa langue même s'il ne lit pas la langue courante. Ils ne se traduisent pas.

### Le composant

[`src/app/language-switcher/language-switcher.ts`](src/app/language-switcher/language-switcher.ts)

```ts
export class LanguageSwitcher {
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);

  /** Langue du build en cours d'exécution ('en' ou 'fr'), fixée à la compilation. */
  protected readonly currentLocale = inject(LOCALE_ID);

  /** URL courante dans l'application (sans le préfixe /fr/), mise à jour à chaque navigation. */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  protected readonly links = computed(() =>
    LOCALES.map((locale) => ({
      ...locale,
      href: new URL(`../${locale.code}${this.url()}`, this.document.baseURI).href,
    })),
  );
}
```

[`src/app/language-switcher/language-switcher.html`](src/app/language-switcher/language-switcher.html)

<!-- prettier-ignore -->
```html
<!-- Liens <a href> classiques (pas routerLink) : changer de langue charge l'autre build. -->
<nav aria-label="Language" i18n-aria-label="@@languageSwitcher.label">
  @for (link of links(); track link.code) {
    <a
      [href]="link.href"
      [hreflang]="link.code"
      [lang]="link.code"
      [aria-current]="link.code === currentLocale ? 'true' : null"
    >
      {{ link.label }}
    </a>
  }
</nav>
```

- **`href` et pas `routerLink`** : le router ne navigue qu'à l'intérieur d'un build. Pour passer
  au build anglais, le navigateur doit charger `/en/about`.
- **Calcul du lien** : `router.url` vaut `/about` (sans le préfixe). `../en/about`, résolu par
  rapport au `<base href="/fr/">`, donne `/en/about`. Ce calcul relatif fonctionne aussi si le
  site est déployé dans un sous-dossier (`/mon-app/fr/` → `/mon-app/en/`).
- `lang` sur chaque lien : un lecteur d'écran prononce « Français » avec l'accent français.
- `hreflang` indique la langue de la page cible ; `aria-current` marque la langue active.

## 8. Développer au quotidien

Le serveur de développement ne sert **qu'une langue à la fois**, à la racine :

```bash
npm start
```

```bash
npm run start:fr
```

Le sélecteur ne peut donc pas fonctionner avec `ng serve`. Pour tester le changement de langue,
on construit toutes les langues et on les sert comme en production :

```bash
npm run preview
```

Ce script lance [`scripts/serve-localized.mjs`](scripts/serve-localized.mjs), un petit serveur
Node.js sans dépendance qui lit les langues dans `angular.json` et applique les règles décrites au
[§10](#10-déployer).

## 9. Tester

Les tests unitaires s'exécutent dans la **langue source** : on teste les textes anglais et le
comportement, pas les traductions (le build les vérifie avec `i18nMissingTranslation`).

[`src/app/app.spec.ts`](src/app/app.spec.ts) (extrait)

```ts
it('should render the plural ICU message on the about page', async () => {
  const page = await render('/about');

  expect(page.querySelector('h1')?.textContent).toBe('About');
  expect(page.textContent).toContain('2 languages available.');
  expect(document.title).toBe('About');
});
```

Pour simuler le build français, on fournit `LOCALE_ID`. Il faut alors enregistrer les données de la
langue, que le vrai build embarque automatiquement :

[`src/app/language-switcher/language-switcher.spec.ts`](src/app/language-switcher/language-switcher.spec.ts)
(extrait)

```ts
beforeAll(() => {
  // Le build `fr` embarque ces données (dates, nombres) ; dans un test, on les enregistre.
  registerLocaleData(localeFr);
});

beforeEach(async () => {
  await TestBed.configureTestingModule({
    imports: [App],
    providers: [provideRouter(routes), { provide: LOCALE_ID, useValue: 'fr' }],
  }).compileComponents();
});
```

> **Piège rencontré :** sans `registerLocaleData(localeFr)`, le pipe `date` lève
> `NG0701: Missing locale data for the locale "fr"`.

Et un test garde la liste `LOCALES` synchronisée avec `angular.json` :

[`src/app/i18n/locales.spec.ts`](src/app/i18n/locales.spec.ts)

```ts
import angularJson from '../../../angular.json';
import { LOCALES } from './locales';

it('should match the locales built by angular.json', () => {
  const { sourceLocale, locales } = angularJson.projects['i18n-demo'].i18n;

  expect(LOCALES.map(({ code }) => code)).toEqual([sourceLocale, ...Object.keys(locales)]);
});
```

```bash
npm test
```

## 10. Déployer

On déploie le dossier `dist/i18n-demo/browser/` tel quel. Le serveur doit :

1. rediriger `/` vers la langue préférée de l'utilisateur (en-tête `Accept-Language`) ;
2. pour `/fr/...`, servir le fichier demandé s'il existe, sinon `/fr/index.html` (pour que le
   rechargement de `/fr/about` fonctionne).

Exemple avec nginx :

```nginx
map $http_accept_language $preferred_locale {
  default en;
  ~^fr    fr;
}

server {
  root /var/www/i18n-demo/browser;

  location ~ ^/(en|fr)/ {
    try_files $uri $uri/ /$1/index.html;
  }

  location / {
    add_header Vary Accept-Language;
    return 302 /$preferred_locale/;
  }
}
```

La redirection initiale se fait **côté serveur**, avant tout chargement d'Angular : c'est plus
rapide qu'une redirection en JavaScript, et les robots la suivent.

**Pour le référencement**, on peut aller plus loin avec du pré-rendu (`ng add @angular/ssr`, qui
gère les builds localisés) et des balises `<link rel="alternate" hreflang="…">` entre les versions
de chaque page.

## 11. Récapitulatif

- [x] `@angular/localize` : aucune dépendance tierce, traductions appliquées au build.
- [x] Identifiants personnalisés (`@@page.element`) sur tous les messages.
- [x] Description pour le traducteur quand le texte seul est ambigu.
- [x] `i18n-<attribut>` pour les attributs, ICU `plural` / `select` pour les variantes.
- [x] `$localize` dans le code TypeScript (ici, les titres de pages).
- [x] Pluriels ICU sur une seule ligne ; espaces des messages vérifiés dans le fichier extrait.
- [x] `sourceLocale` explicite et `i18nMissingTranslation: "error"`.
- [x] Chaque `<trans-unit>` du fichier traduit a son `<target>`.
- [x] Sélecteur fait de liens `href` vers l'autre build, calculés depuis `<base href>`.
- [x] Libellés de langue en autonymes, avec `lang`, `hreflang` et `aria-current`.
- [x] Liste des langues testée contre `angular.json`.
- [x] Tests dans la langue source ; `registerLocaleData()` pour simuler une autre langue.
- [x] Redirection initiale et repli sur `index.html` par langue, côté serveur.
