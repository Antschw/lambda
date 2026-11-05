import { Routes } from '@angular/router';

// Pas de langue dans les routes : chaque langue est un build servi sous son propre
// chemin (/en/, /fr/), qui sert de <base href>. Les URL ci-dessous s'y ajoutent.
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
