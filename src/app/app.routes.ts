import { Routes } from '@angular/router';

import { langResolver, redirectToPreferredLang, supportedLangGuard } from './i18n/lang-routing';

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
