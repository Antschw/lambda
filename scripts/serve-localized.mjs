// Serveur de prévisualisation du build localisé (`npm run preview`), sans dépendance.
// Il reproduit ce que doit faire le serveur de production (voir le tutoriel, § Déployer) :
//   - `/` (ou une langue inconnue) → redirection vers la langue préférée du navigateur ;
//   - `/fr/...` → fichier du build `fr` s'il existe, sinon `fr/index.html` (routes Angular).
import { readFileSync } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join, resolve, sep } from 'node:path';

const PORT = Number(process.env.PORT ?? 4200);
const PROJECT = 'i18n-demo';

// Les langues viennent d'angular.json : une seule source de vérité.
const angularJson = JSON.parse(readFileSync('angular.json', 'utf8'));
const { sourceLocale, locales } = angularJson.projects[PROJECT].i18n;
const LOCALES = [sourceLocale, ...Object.keys(locales)];
const ROOT = resolve('dist', PROJECT, 'browser');

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
};

/** Première langue de l'en-tête Accept-Language que l'application supporte. */
function preferredLocale(acceptLanguage = '') {
  const requested = acceptLanguage
    .split(',')
    .map((part) => part.split(';')[0].trim().toLowerCase().split('-')[0]);
  return requested.find((code) => LOCALES.includes(code)) ?? sourceLocale;
}

async function isFile(path) {
  return (await stat(path).catch(() => null))?.isFile() ?? false;
}

createServer(async (req, res) => {
  const { pathname } = new URL(req.url ?? '/', 'http://localhost');
  const locale = pathname.split('/')[1];

  if (!LOCALES.includes(locale)) {
    res.writeHead(302, {
      Location: `/${preferredLocale(req.headers['accept-language'])}/`,
      Vary: 'Accept-Language',
    });
    res.end();
    return;
  }

  const requested = join(ROOT, decodeURIComponent(pathname));
  const file =
    requested.startsWith(ROOT + sep) && (await isFile(requested))
      ? requested
      : join(ROOT, locale, 'index.html');

  res.writeHead(200, {
    'Content-Type': CONTENT_TYPES[extname(file)] ?? 'application/octet-stream',
  });
  res.end(await readFile(file));
}).listen(PORT, () => {
  console.log(`Build localisé (${LOCALES.join(', ')}) servi sur http://localhost:${PORT}/`);
});
