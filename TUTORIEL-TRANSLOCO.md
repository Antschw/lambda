# Mettre en place Transloco dans Angular — tutoriel

Ce tutoriel explique pas à pas comment internationaliser (i18n) une application Angular avec
[Transloco](https://jsverse.gitbook.io/transloco), en suivant les standards actuels d'Angular et de
TypeScript. Le code de ce dépôt est le résultat final : chaque étape renvoie au fichier concerné.

**Versions utilisées**

| Outil      | Version                                                 |
| ---------- | ------------------------------------------------------- |
| Angular    | 21.2 — **LTS** (tag npm `v21-lts`)                      |
| Transloco  | 8.4 (`@jsverse/transloco`, dernière stable)             |
| TypeScript | 5.9                                                     |
| Node.js    | `^20.19.0`, `^22.12.0` ou `>=24` (exigé par Angular 21) |

> **Pourquoi Angular 21 ?** C'est la version LTS la plus récente (support jusqu'en mai 2027). La 22
> est la version stable courante, mais pas encore en LTS. Le code de ce tutoriel est déjà prêt
> pour la 22 : voir les remarques sur `withFetch()` et `OnPush` plus bas.

---

## Sommaire

1. [Créer le projet](#1-créer-le-projet)
2. [Installer Transloco](#2-installer-transloco)
3. [Écrire les fichiers de traduction](#3-écrire-les-fichiers-de-traduction)
4. [Centraliser la configuration](#4-centraliser-la-configuration)
5. [Écrire le loader](#5-écrire-le-loader)
6. [Enregistrer Transloco dans l'application](#6-enregistrer-transloco-dans-lapplication)
7. [Traduire dans un template](#7-traduire-dans-un-template)
8. [Changer de langue](#8-changer-de-langue)
9. [Synchroniser `<html lang>`](#9-synchroniser-html-lang)
10. [Tester](#10-tester)
11. [Récapitulatif des bonnes pratiques](#11-récapitulatif-des-bonnes-pratiques)
12. [Aller plus loin](#12-aller-plus-loin)

---

## 1. Créer le projet

On épingle la version majeure de la CLI pour rester sur la LTS :

```bash
npx @angular/cli@21 new transloco-demo --style=css --ssr=false
```

Un projet Angular 21 est, par défaut :

- **standalone** (plus de `NgModule`) ;
- **zoneless** (pas de `zone.js`, la détection de changements repose sur les signals) ;
- en TypeScript **strict** (`strict` et `strictTemplates` dans `tsconfig.json`) ;
- testé avec **Vitest**.

En revanche, la stratégie de détection de changements par défaut est encore `Default` : on déclare
donc `OnPush` sur chaque composant (voir [§7](#7-traduire-dans-un-template)).

> **Si `npm install` échoue** avec `Cannot read properties of null (reading 'edgesOut')` : c'est un
> bug de npm 10.9 rencontré lors de la génération de ce projet. Relancez l'installation avec
> npm 11 (`npx npm@11 install`). Une fois le `package-lock.json` créé, `npm install` et `npm ci`
> fonctionnent avec npm 10.

## 2. Installer Transloco

```bash
npx ng add @jsverse/transloco --langs=en,fr
```

Le schematic :

- ajoute `@jsverse/transloco` aux dépendances ;
- crée `public/i18n/en.json` et `public/i18n/fr.json` ;
- crée un loader HTTP dans `src/app/transloco-loader.ts` ;
- ajoute `provideHttpClient()` et `provideTransloco(...)` dans `src/app/app.config.ts` ;
- crée un fichier `transloco.config.ts` **à la racine du projet**.

On part de ce résultat, puis on l'ajuste :

| Généré par le schematic                       | Ce qu'on fait ici                                         | Pourquoi                                                                                                                                                                 |
| --------------------------------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `provideHttpClient()`                         | **Complété** en `provideHttpClient(withFetch())`          | Le loader utilise `HttpClient`. `withFetch()` utilise l'API `fetch` plutôt que XHR : c'est recommandé par Angular et c'est le comportement par défaut à partir de la 22. |
| Config inline dans `app.config.ts`            | **Déplacée** dans `src/app/i18n/i18n.config.ts` (étape 4) | Elle est partagée avec les tests (voir [§10](#10-tester)).                                                                                                               |
| `src/app/transloco-loader.ts`                 | **Déplacé** dans `src/app/i18n/`                          | Tout ce qui concerne l'i18n est regroupé dans un dossier.                                                                                                                |
| `transloco.config.ts` (à la racine du projet) | **Supprimé**                                              | Il n'est lu ni par Angular ni par Transloco à l'exécution : il sert uniquement aux outils en ligne de commande (voir l'encadré ci-dessous).                              |

> **Deux fichiers à ne pas confondre**
>
> | Fichier                                  | Lu par                                                          | Dans ce projet |
> | ---------------------------------------- | --------------------------------------------------------------- | -------------- |
> | `transloco.config.ts` (racine du projet) | Les outils CLI : `@jsverse/transloco-keys-manager`, scoped libs | Supprimé       |
> | `src/app/i18n/i18n.config.ts`            | L'application et les tests, via `provideTransloco()`            | Étape 4        |
>
> Le premier ne sert à rien tant qu'on n'utilise pas ces outils. Pour les ajouter plus tard, il
> suffit de relancer `ng add` ou de recréer ce fichier (voir [§12](#12-aller-plus-loin)).

## 3. Écrire les fichiers de traduction

Les fichiers sont dans `public/i18n/`. Tout ce qui est dans `public/` est copié tel quel à la racine
du build, donc `public/i18n/fr.json` est servi à l'URL `i18n/fr.json`.

[`public/i18n/en.json`](public/i18n/en.json)

```json
{
  "title": "Transloco demo",
  "greeting": "Hello {{ name }}!",
  "description": "This page is translated with Transloco.",
  "languageSwitcher": {
    "label": "Language"
  }
}
```

[`public/i18n/fr.json`](public/i18n/fr.json)

```json
{
  "title": "Démo Transloco",
  "greeting": "Bonjour {{ name }} !",
  "description": "Cette page est traduite avec Transloco.",
  "languageSwitcher": {
    "label": "Langue"
  }
}
```

À retenir :

- **Mêmes clés dans chaque langue.** Une clé absente d'une langue s'affiche telle quelle
  (`languageSwitcher.label`) et produit un avertissement en mode développement.
- **Paramètres** : `{{ name }}` est remplacé à l'exécution par la valeur passée au moment de la
  traduction.
- **Clés imbriquées** : on y accède avec un point, `languageSwitcher.label`. Regrouper les clés par
  composant ou par fonctionnalité garde les fichiers lisibles quand ils grossissent.

## 4. Centraliser la configuration

On crée un fichier dédié à la configuration i18n de l'application. Son nom suit la convention de
`app.config.ts` (et se distingue du `transloco.config.ts` des outils CLI, voir l'étape 2).

[`src/app/i18n/i18n.config.ts`](src/app/i18n/i18n.config.ts)

```ts
import { isDevMode } from '@angular/core';
import { translocoConfig } from '@jsverse/transloco';

/** Langues supportées par l'application (source unique de vérité). */
export const AVAILABLE_LANGS = ['en', 'fr'] as const;

/** Union des codes de langue supportés : `'en' | 'fr'`. */
export type Lang = (typeof AVAILABLE_LANGS)[number];

/** Configuration Transloco partagée par l'application et les tests. */
export const appTranslocoConfig = translocoConfig({
  availableLangs: [...AVAILABLE_LANGS],
  defaultLang: 'en' satisfies Lang,
  // Met à jour les vues quand la langue change à l'exécution.
  reRenderOnLangChange: true,
  // Masque les avertissements de clés manquantes en production.
  prodMode: !isDevMode(),
});
```

Points TypeScript :

- `as const` fige le tableau en tuple `readonly ['en', 'fr']`, ce qui permet d'en dériver le type
  `Lang = 'en' | 'fr'`. Les langues ne sont déclarées qu'**une seule fois** : ajouter `'es'` au
  tableau met à jour le type, la configuration et le sélecteur de langue.
- `satisfies Lang` vérifie à la compilation que la langue par défaut fait partie des langues
  supportées.
- `translocoConfig()` complète la configuration avec les valeurs par défaut de Transloco et la type.

Options utilisées :

| Option                 | Rôle                                                                                                  |
| ---------------------- | ----------------------------------------------------------------------------------------------------- |
| `availableLangs`       | Langues que l'application accepte.                                                                    |
| `defaultLang`          | Langue active au démarrage.                                                                           |
| `reRenderOnLangChange` | **Indispensable** si l'utilisateur peut changer de langue sans recharger la page. Par défaut `false`. |
| `prodMode`             | Coupe les logs de Transloco (clés manquantes, etc.) en production.                                    |

## 5. Écrire le loader

Le loader indique à Transloco **comment** récupérer les traductions d'une langue. Il n'est appelé
qu'au premier besoin d'une langue : le français n'est téléchargé que si l'utilisateur le choisit.

[`src/app/i18n/transloco-loader.ts`](src/app/i18n/transloco-loader.ts)

```ts
import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Translation, TranslocoLoader } from '@jsverse/transloco';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class TranslocoHttpLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);

  getTranslation(lang: string): Observable<Translation> {
    return this.http.get<Translation>(`i18n/${lang}.json`);
  }
}
```

- `inject()` plutôt que l'injection par constructeur : c'est la forme recommandée par Angular.
- `private readonly` : la dépendance n'est ni exposée ni réassignable.
- **URL relative** (`i18n/...` et non `/i18n/...`) : elle est résolue par rapport au `<base href>`,
  donc l'application continue de fonctionner si elle est déployée dans un sous-chemin
  (`https://exemple.com/mon-app/`).

## 6. Enregistrer Transloco dans l'application

[`src/app/app.config.ts`](src/app/app.config.ts)

```ts
import { provideHttpClient, withFetch } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideTransloco } from '@jsverse/transloco';

import { appTranslocoConfig } from './i18n/i18n.config';
import { TranslocoHttpLoader } from './i18n/transloco-loader';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withFetch()),
    provideTransloco({
      config: appTranslocoConfig,
      loader: TranslocoHttpLoader,
    }),
  ],
};
```

- `provideTransloco()` est l'API standalone. Il n'y a plus besoin de `TranslocoRootModule`
  (ancienne approche à base de `NgModule`).
- `provideHttpClient(withFetch())` : depuis Angular 21, `HttpClient` serait injectable sans cette
  ligne, mais il utiliserait XHR. `withFetch()` aligne l'application sur le comportement d'Angular
  22 ; lors de la montée de version, la ligne pourra être simplifiée (`withFetch()` y est déprécié
  car devenu inutile).

## 7. Traduire dans un template

### La directive structurelle (recommandée)

[`src/app/app.html`](src/app/app.html)

```html
<main *transloco="let t">
  <h1>{{ t('title') }}</h1>
  <p>{{ t('greeting', { name }) }}</p>
  <p>{{ t('description') }}</p>
</main>
```

[`src/app/app.ts`](src/app/app.ts) (extrait)

```ts
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

@Component({
  selector: 'app-root',
  imports: [TranslocoDirective, LanguageSwitcher],
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly name = 'Angular';
}
```

- `*transloco="let t"` expose une fonction `t(clé, paramètres?)` dans le bloc.
- La directive crée **une seule souscription** pour tout le bloc, quel que soit le nombre de clés :
  c'est l'approche la plus performante, et celle recommandée par la documentation Transloco.
- Le contenu n'est affiché **qu'une fois les traductions chargées** : pas de clé brute qui clignote
  au démarrage.
- On importe uniquement `TranslocoDirective` (pas `TranslocoModule`) : le composant ne dépend que de
  ce qu'il utilise.
- `protected` : la propriété est accessible au template sans faire partie de l'API publique du
  composant.
- `ChangeDetectionStrategy.OnPush` : recommandé par Angular, et devenu le défaut en Angular 22. Les
  vues traduites se mettent bien à jour au changement de langue, Transloco notifiant Angular
  lui-même.

Option utile quand toutes les clés d'un bloc partagent un préfixe :

```html
<nav *transloco="let t; prefix: 'languageSwitcher'">{{ t('label') }}</nav>
```

### Alternatives

Le pipe, pratique pour une clé isolée :

```html
<p>{{ 'greeting' | transloco: { name } }}</p>
```

(importer `TranslocoPipe` dans le composant)

Dans le code TypeScript, avec un **signal** qui se met à jour au changement de langue :

```ts
import { translateSignal } from '@jsverse/transloco';

export class Example {
  protected readonly title = translateSignal('title');
}
```

> **Piège :** `TranslocoService.translate('title')` est synchrone. Appelé avant que le fichier de
> langue soit chargé, il renvoie la clé brute (`'title'`). Préférez la directive, le pipe,
> `translateSignal()` ou `selectTranslate()` (observable), qui attendent le chargement.

## 8. Changer de langue

[`src/app/language-switcher/language-switcher.ts`](src/app/language-switcher/language-switcher.ts)

```ts
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';

import { AVAILABLE_LANGS, Lang } from '../i18n/i18n.config';

@Component({
  selector: 'app-language-switcher',
  imports: [TranslocoDirective],
  templateUrl: './language-switcher.html',
  styleUrl: './language-switcher.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LanguageSwitcher {
  private readonly transloco = inject(TranslocoService);

  protected readonly langs = AVAILABLE_LANGS;
  protected readonly activeLang = this.transloco.activeLang;

  protected changeLang(lang: Lang): void {
    this.transloco.setActiveLang(lang);
  }
}
```

[`src/app/language-switcher/language-switcher.html`](src/app/language-switcher/language-switcher.html)

<!-- prettier-ignore -->
```html
<nav *transloco="let t" [aria-label]="t('languageSwitcher.label')">
  @for (lang of langs; track lang) {
    <button type="button" [aria-pressed]="lang === activeLang()" (click)="changeLang(lang)">
      {{ lang }}
    </button>
  }
</nav>
```

- `TranslocoService.activeLang` est un **signal** (depuis Transloco 8.3) : pas d'`async` pipe ni de
  souscription manuelle, et il fonctionne naturellement avec `OnPush` / zoneless.
- `setActiveLang()` charge la langue si besoin, puis toutes les vues se mettent à jour grâce à
  `reRenderOnLangChange: true`.
- `changeLang(lang: Lang)` : le type empêche d'appeler la méthode avec une langue non supportée.
- Syntaxe de template moderne : `@for` avec `track`, liaison directe `[aria-label]` /
  `[aria-pressed]` (sans le préfixe `attr.`, possible depuis Angular 20).
- Accessibilité : `aria-pressed` indique la langue active aux lecteurs d'écran, et le libellé du
  `<nav>` est lui-même traduit.

## 9. Synchroniser `<html lang>`

L'attribut `lang` de la page doit suivre la langue affichée : les lecteurs d'écran l'utilisent pour
choisir la prononciation, et les moteurs de recherche pour indexer la page.

[`src/app/app.ts`](src/app/app.ts)

```ts
export class App {
  private readonly document = inject(DOCUMENT);
  private readonly activeLang = inject(TranslocoService).activeLang;

  protected readonly name = 'Angular';

  constructor() {
    effect(() => {
      this.document.documentElement.lang = this.activeLang();
    });
  }
}
```

- `DOCUMENT` est importé depuis `@angular/core` (et non plus `@angular/common`).
- `effect()` se relance automatiquement à chaque changement du signal `activeLang`.

## 10. Tester

On ne veut pas de requêtes HTTP dans les tests unitaires. Transloco fournit
`TranslocoTestingModule`, qui charge les traductions de façon synchrone. On l'encapsule dans un
helper qui réutilise les **vrais** fichiers JSON et la **même configuration** que l'application :

[`src/app/i18n/transloco-testing.ts`](src/app/i18n/transloco-testing.ts)

```ts
import { TranslocoTestingModule, TranslocoTestingOptions } from '@jsverse/transloco';

import en from '../../../public/i18n/en.json';
import fr from '../../../public/i18n/fr.json';
import { appTranslocoConfig } from './i18n.config';

export function getTranslocoModule(options: TranslocoTestingOptions = {}) {
  return TranslocoTestingModule.forRoot({
    langs: { en, fr },
    translocoConfig: appTranslocoConfig,
    preloadLangs: true,
    ...options,
  });
}
```

> **Piège rencontré en écrivant cette démo :** si on passe au module de test une configuration
> écrite à la main sans `reRenderOnLangChange: true`, les tests de changement de langue échouent
> alors que l'application fonctionne. Réutiliser `appTranslocoConfig` évite cet écart entre
> l'application et les tests.

Utilisation dans un test ([`src/app/app.spec.ts`](src/app/app.spec.ts)) :

```ts
beforeEach(async () => {
  await TestBed.configureTestingModule({
    imports: [App, getTranslocoModule()],
  }).compileComponents();
});

it('should re-render the translations when the language changes', async () => {
  const fixture = TestBed.createComponent(App);
  await fixture.whenStable();

  TestBed.inject(TranslocoService).setActiveLang('fr');
  await fixture.whenStable();

  const compiled = fixture.nativeElement as HTMLElement;
  expect(compiled.querySelector('h1')?.textContent).toBe('Démo Transloco');
});
```

Tester sur les vrais textes (et pas sur les clés) permet aussi de détecter une clé manquante ou mal
orthographiée.

```bash
npm test
```

## 11. Récapitulatif des bonnes pratiques

- [x] Angular en version LTS, CLI épinglée sur la version majeure (`@angular/cli@21`).
- [x] `provideTransloco()` (standalone), pas de `TranslocoRootModule`.
- [x] `provideHttpClient(withFetch())` pour charger les traductions avec `fetch`.
- [x] Langues déclarées une seule fois (`as const`) et type `Lang` dérivé.
- [x] Configuration partagée entre l'application et les tests (`i18n.config.ts`).
- [x] Fichiers de traduction dans `public/i18n/`, chargés à la demande, via une URL relative.
- [x] `reRenderOnLangChange: true` dès que la langue peut changer sans recharger la page.
- [x] `prodMode: !isDevMode()` pour couper les logs en production.
- [x] Directive structurelle `*transloco="let t"` dans les templates ; `translateSignal()` dans le
      code.
- [x] Pas de `TranslocoService.translate()` avant le chargement des traductions.
- [x] Import ciblé (`TranslocoDirective`, `TranslocoPipe`) plutôt que `TranslocoModule`.
- [x] Composants en `ChangeDetectionStrategy.OnPush`.
- [x] `TranslocoService.activeLang` (signal) plutôt que des souscriptions manuelles.
- [x] `<html lang>` synchronisé avec la langue active.
- [x] Tests sur les vrais textes avec `TranslocoTestingModule`.

## 12. Aller plus loin

Pour garder la démo minimale, les points suivants ne sont pas implémentés :

| Besoin                                                         | Solution                                                                                    |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| Afficher la traduction d'une autre langue quand une clé manque | Options `fallbackLang` et `missingHandler: { useFallbackTranslation: true }`                |
| Retenir la langue choisie entre deux visites                   | `@jsverse/transloco-persist-lang`                                                           |
| Détecter la langue du navigateur au démarrage                  | `getBrowserLang()` exporté par `@jsverse/transloco`                                         |
| Charger les traductions par fonctionnalité (lazy loading)      | Les _scopes_ Transloco (`provideTranslocoScope()`)                                          |
| Détecter les clés manquantes ou inutilisées                    | `@jsverse/transloco-keys-manager` (nécessite le `transloco.config.ts` à la racine, étape 2) |
| Pluriels, genres (syntaxe ICU)                                 | `@jsverse/transloco-messageformat`                                                          |
| Formater dates, nombres, devises selon la langue               | `@jsverse/transloco-locale`                                                                 |
| Rendu serveur (SSR)                                            | Le loader doit utiliser une URL absolue côté serveur (voir la documentation Transloco)      |

Documentation officielle : <https://jsverse.gitbook.io/transloco>
