# La langue dans l'URL avec Transloco — tutoriel

Ce tutoriel fait suite à [TUTORIEL-TRANSLOCO.md](TUTORIEL-TRANSLOCO.md), qui met en place Transloco
avec un simple sélecteur de langue. On part de ce résultat et on fait porter la langue par l'URL :

```text
/            → redirection vers /fr ou /en (langue du navigateur)
/fr          → accueil en français
/en/about    → page « About » en anglais
/de/about    → langue non supportée : redirection vers la langue préférée
/fr/inconnu  → page inconnue : redirection vers /fr
```

**Versions** : Angular 21.2 (LTS), Transloco 8.4, TypeScript 5.9.

---

## Sommaire

1. [Pourquoi mettre la langue dans l'URL](#1-pourquoi-mettre-la-langue-dans-lurl)
2. [Le principe : l'URL est la source de vérité](#2-le-principe--lurl-est-la-source-de-vérité)
3. [Compléter la configuration des langues](#3-compléter-la-configuration-des-langues)
4. [Déclarer les routes](#4-déclarer-les-routes)
5. [Guard, resolver et redirection](#5-guard-resolver-et-redirection)
6. [Enregistrer le router](#6-enregistrer-le-router)
7. [Des liens de navigation qui gardent la langue](#7-des-liens-de-navigation-qui-gardent-la-langue)
8. [Le sélecteur de langue devient une liste de liens](#8-le-sélecteur-de-langue-devient-une-liste-de-liens)
9. [Tester](#9-tester)
10. [Déployer](#10-déployer)
11. [Référencement (SEO)](#11-référencement-seo)
12. [Récapitulatif](#12-récapitulatif)

---

## 1. Pourquoi mettre la langue dans l'URL

Avec le sélecteur de la première démo, la langue est un état interne de l'application. Cela suffit
pour une application derrière un login, mais pas pour un site public :

| Langue dans l'état de l'application               | Langue dans l'URL                                          |
| ------------------------------------------------- | ---------------------------------------------------------- |
| Un lien partagé s'ouvre dans la langue par défaut | Un lien `/fr/about` s'ouvre en français pour tout le monde |
| Une seule version indexée par les moteurs         | Une URL indexable par langue                               |
| Précédent / Suivant ne changent pas la langue     | L'historique du navigateur inclut la langue                |
| Langue perdue au rechargement (sauf stockage)     | Le rechargement garde la langue                            |

Google recommande explicitement une URL distincte par langue : il n'utilise ni les cookies ni la
langue du navigateur pour choisir la version à indexer.

## 2. Le principe : l'URL est la source de vérité

La règle qui structure tout le code : **on ne change plus la langue en appelant
`setActiveLang()` depuis un bouton**. On change l'URL, et le router active la langue.

```text
clic sur « EN »  →  navigation vers /en/about  →  resolver de la route :lang
                                                   ├─ charge en.json
                                                   └─ setActiveLang('en')  →  les vues se mettent à jour
```

Il n'y a ainsi qu'un seul chemin pour changer de langue, qu'il s'agisse d'un clic, d'un lien
partagé, d'un rechargement ou du bouton Précédent.

## 3. Compléter la configuration des langues

[`src/app/i18n/i18n.config.ts`](src/app/i18n/i18n.config.ts) (ajouts)

```ts
/** Langue utilisée quand ni l'URL ni le navigateur n'en fournissent une supportée. */
export const DEFAULT_LANG: Lang = 'en';

/** Vérifie qu'une valeur (segment d'URL, langue du navigateur…) est une langue supportée. */
export function isLang(value: unknown): value is Lang {
  return typeof value === 'string' && (AVAILABLE_LANGS as readonly string[]).includes(value);
}
```

`isLang` est un **type guard** : après `if (isLang(x))`, TypeScript sait que `x` est de type
`'en' | 'fr'`. Les segments d'URL sont des chaînes quelconques saisies par l'utilisateur : on les
valide avant de s'en servir, sans `as Lang`.

## 4. Déclarer les routes

[`src/app/app.routes.ts`](src/app/app.routes.ts)

```ts
export const routes: Routes = [
  {
    // Toutes les pages sont préfixées par la langue : /en, /fr/about…
    path: ':lang',
    canMatch: [supportedLangGuard],
    resolve: { lang: langResolver },
    children: [
      { path: '', loadComponent: () => import('./pages/home/home').then((m) => m.Home) },
      { path: 'about', loadComponent: () => import('./pages/about/about').then((m) => m.About) },
      // Page inconnue dans une langue connue : retour à l'accueil de cette langue.
      { path: '**', redirectTo: '' },
    ],
  },
  // `/`, `/de/...` : redirection vers la langue préférée.
  { path: '**', redirectTo: redirectToPreferredLang },
];
```

- Une route parente `:lang` **sans composant** regroupe toutes les pages. Ajouter une page, c'est
  ajouter un enfant : elle hérite automatiquement de la gestion de la langue.
- Les pages sont **chargées à la demande** (`loadComponent`) : chacune a son propre fichier
  JavaScript, téléchargé à la première visite.
- L'ordre compte : la route `**` de fin attrape tout ce que la route `:lang` a refusé.

Les pages elles-mêmes ne savent rien de l'URL ; elles traduisent comme avant. L'option `prefix`
évite de répéter `home.` devant chaque clé :

[`src/app/pages/home/home.html`](src/app/pages/home/home.html)

```html
<ng-container *transloco="let t; prefix: 'home'">
  <h1>{{ t('title') }}</h1>
  <p>{{ t('greeting', { name }) }}</p>
  <p>{{ t('description') }}</p>
</ng-container>
```

Les clés sont regroupées par page dans [`public/i18n/en.json`](public/i18n/en.json) et
[`public/i18n/fr.json`](public/i18n/fr.json) (`nav.*`, `home.*`, `about.*`).

## 5. Guard, resolver et redirection

Les trois fonctions sont regroupées dans
[`src/app/i18n/lang-routing.ts`](src/app/i18n/lang-routing.ts). Ce sont des fonctions et non des
classes : c'est la forme recommandée par Angular pour les guards, resolvers et redirections.

### Le guard : accepter uniquement les langues supportées

```ts
export const supportedLangGuard: CanMatchFn = (_route, segments) => isLang(segments[0]?.path);
```

Pourquoi `canMatch` plutôt que `canActivate` ? Un `canActivate` qui refuse bloque la navigation.
Un `canMatch` qui refuse dit seulement « cette route ne correspond pas », et le router essaie les
suivantes. `/de/about` tombe donc naturellement sur la route `**` et sa redirection.

Le guard ne fait que valider : il n'a pas d'effet de bord.

### Le resolver : charger puis activer la langue

```ts
export const langResolver: ResolveFn<Lang> = (route) => {
  const param = route.paramMap.get('lang');
  const lang = isLang(param) ? param : DEFAULT_LANG;
  const transloco = inject(TranslocoService);

  return transloco.load(lang).pipe(
    tap(() => transloco.setActiveLang(lang)),
    map(() => lang),
  );
};
```

- Le router attend la fin du resolver avant d'afficher la page : les traductions sont **toujours
  chargées** quand la page apparaît.
- On charge **avant** d'activer. Si on activait d'abord, la page courante resterait vide le temps
  du téléchargement du fichier JSON.
- Par défaut, un resolver ne se relance que si les paramètres de sa route changent : il tourne en
  passant de `/fr` à `/en/about`, mais pas de `/fr` à `/fr/about`. C'est exactement le
  comportement voulu.

### La redirection : choisir une langue quand l'URL n'en a pas

```ts
export const redirectToPreferredLang: RedirectFunction = () => {
  const browserLang = getBrowserLang();
  return `/${isLang(browserLang) ? browserLang : DEFAULT_LANG}`;
};
```

- `redirectTo` accepte une fonction depuis Angular 18 : plus besoin d'un composant ou d'un guard
  qui redirige.
- `getBrowserLang()` (fourni par Transloco) renvoie `fr` pour un navigateur en `fr-FR`.
- La langue du navigateur ne sert **qu'à** cette redirection initiale. Ensuite, c'est l'URL qui
  décide : un utilisateur au navigateur anglais qui ouvre `/fr/about` voit bien la page en
  français.

## 6. Enregistrer le router

[`src/app/app.config.ts`](src/app/app.config.ts)

```ts
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withFetch()),
    provideTransloco({
      config: appTranslocoConfig,
      loader: TranslocoHttpLoader,
    }),
  ],
};
```

La configuration Transloco ne change pas. `defaultLang` reste utile : c'est la langue active avant
la première navigation, et celle des tests.

## 7. Des liens de navigation qui gardent la langue

[`src/app/app.html`](src/app/app.html) (extrait)

```html
<a
  [routerLink]="['/', activeLang(), 'about']"
  routerLinkActive="active"
  ariaCurrentWhenActive="page"
  >{{ t('nav.about') }}</a
>
```

- `activeLang` est le signal de `TranslocoService` ; il est à jour puisque le resolver l'a réglé.
- `ariaCurrentWhenActive="page"` ajoute `aria-current="page"` au lien de la page courante pour
  les lecteurs d'écran.
- `<router-outlet />` remplace le contenu de la page dans `app.html` ; l'en-tête (navigation et
  sélecteur) reste commun à toutes les pages.

## 8. Le sélecteur de langue devient une liste de liens

[`src/app/language-switcher/language-switcher.ts`](src/app/language-switcher/language-switcher.ts)

```ts
export class LanguageSwitcher {
  private readonly router = inject(Router);

  protected readonly activeLang = inject(TranslocoService).activeLang;

  /** URL courante, mise à jour à chaque navigation. */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  /**
   * Un lien par langue : la même page, avec le premier segment de l'URL remplacé.
   * `/fr/about?x=1` donne `/en/about?x=1` et `/fr/about?x=1`.
   */
  protected readonly links = computed(() => {
    const current = this.router.parseUrl(this.url());
    const [, ...pageSegments] = current.root.children[PRIMARY_OUTLET]?.segments ?? [];

    return AVAILABLE_LANGS.map((lang) => ({
      lang,
      url: this.router.createUrlTree(['/', lang, ...pageSegments.map(({ path }) => path)], {
        queryParams: current.queryParams,
        fragment: current.fragment ?? undefined,
      }),
    }));
  });
}
```

[`src/app/language-switcher/language-switcher.html`](src/app/language-switcher/language-switcher.html)

<!-- prettier-ignore -->
```html
<nav *transloco="let t" [aria-label]="t('languageSwitcher.label')">
  @for (link of links(); track link.lang) {
    <a
      [routerLink]="link.url"
      [hreflang]="link.lang"
      [aria-current]="link.lang === activeLang() ? 'true' : null"
    >
      {{ link.lang }}
    </a>
  }
</nav>
```

- **Des liens, pas des boutons** : le changement de langue est une navigation. On peut ouvrir une
  langue dans un nouvel onglet, copier le lien, et les robots peuvent suivre ces liens.
- **On reste sur la même page** : seul le premier segment change ; le reste du chemin, les
  paramètres (`?tab=1`) et l'ancre (`#section`) sont conservés.
- `toSignal()` transforme le flux d'événements du router en signal, et `computed()` recalcule les
  liens à chaque navigation, sans souscription manuelle.
- `routerLink` accepte directement un `UrlTree`, construit avec `createUrlTree()` qui gère
  l'encodage.
- `hreflang` indique la langue de la page cible ; `aria-current` signale la langue active.

## 9. Tester

Les tests passent maintenant par le router : on navigue vers une URL et on vérifie la page obtenue.

[`src/app/app.spec.ts`](src/app/app.spec.ts) (extrait)

```ts
beforeEach(async () => {
  await TestBed.configureTestingModule({
    imports: [App, getTranslocoModule()],
    providers: [provideRouter(routes)],
  }).compileComponents();
  router = TestBed.inject(Router);
});

it('should redirect an unsupported language to the preferred one', async () => {
  await render('/de/about');

  expect(router.url).toBe('/en');
});
```

[`src/app/language-switcher/language-switcher.spec.ts`](src/app/language-switcher/language-switcher.spec.ts)
(extrait)

```ts
it('should change the URL and the language on click', async () => {
  const page = await render('/fr/about');

  switcherLinks(page)[0].click();
  // whenStable() attend aussi la fin de la navigation déclenchée par le clic.
  await fixture.whenStable();

  expect(TestBed.inject(Router).url).toBe('/en/about');
  expect(TestBed.inject(TranslocoService).getActiveLang()).toBe('en');
});
```

> **Piège rencontré :** dans une application zoneless, il ne faut pas attendre une navigation avec
> un `setTimeout` ou un second `navigateByUrl()` (qui annule la navigation en cours).
> `fixture.whenStable()` attend toutes les tâches en attente d'Angular, navigations comprises.

Cas couverts : redirection de `/`, langue lue dans l'URL, langue non supportée, page inconnue,
liens du sélecteur (chemin et paramètres conservés), et changement de langue au clic.

## 10. Déployer

En développement, `ng serve` renvoie `index.html` pour toutes les URL. En production, le serveur
doit faire de même, sinon un rechargement de `/fr/about` donne une erreur 404. Exemple avec nginx :

```nginx
location / {
  try_files $uri $uri/ /index.html;
}
```

## 11. Référencement (SEO)

Mettre la langue dans l'URL est la première étape. Pour qu'un moteur de recherche indexe
correctement chaque langue, il faut aussi :

- **du rendu côté serveur ou du pré-rendu** (`ng add @angular/ssr`) : le robot reçoit directement
  le HTML traduit au lieu d'une page vide à construire en JavaScript ;
- des balises **`<link rel="alternate" hreflang="…">`** dans le `<head>` de chaque page, pointant
  vers ses équivalents dans les autres langues (plus `hreflang="x-default"` vers `/`) ;
- un **titre de page traduit** (par exemple avec une `TitleStrategy` personnalisée qui utilise
  Transloco).

Ces points dépassent le cadre de cette démo.

## 12. Récapitulatif

- [x] La langue est le premier segment de l'URL ; l'URL est la seule source de vérité.
- [x] Route parente `:lang` sans composant, pages enfants chargées à la demande.
- [x] `canMatch` pour valider la langue (sans effet de bord), avec repli sur la route `**`.
- [x] Resolver qui charge les traductions **puis** active la langue : pas de page vide.
- [x] `redirectTo` en fonction pour rediriger `/` vers la langue du navigateur.
- [x] Sélecteur fait de liens (`routerLink`, `hreflang`, `aria-current`) qui gardent la page,
      les paramètres et l'ancre.
- [x] `toSignal()` + `computed()` plutôt que des souscriptions manuelles.
- [x] Guards, resolvers et redirections sous forme de fonctions.
- [x] Tests de bout en bout du routage avec `provideRouter()` et `fixture.whenStable()`.
- [x] Serveur de production configuré pour renvoyer `index.html` sur toutes les URL.
