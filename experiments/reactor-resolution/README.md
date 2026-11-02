# Expérience — d'où Maven tire `b` quand on construit `a` avec `-pl` / `-rf` (Maven 3.10.0)

Projet jouet `projet/` : `parent` → modules `a` et `b`, **`a` dépend de `b`** (`1.0-SNAPSHOT`).
Tout a été exécuté avec **Apache Maven 3.10.0** (`c43a36b8`), Java 21.

## Résultat en bref

| #  | Hypothèse | Verdict |
|----|-----------|---------|
| H1 | Avec `-pl a` ou `-rf :a`, `b` est hors reactor ; `b/target/` n'est **jamais** utilisé | ✅ **Confirmée** |
| H2 | `b` est pris dans le dépôt local, sinon sur le dépôt distant (ou distant si SNAPSHOT plus récent) | ✅ **Confirmée** |
| H3 | Avec `-pl a -am`, `a` utilise `b/target/classes` produit pendant la commande | ⚠️ **Partiellement** : avec `package`, c'est le **jar** `b/target/b-1.0-SNAPSHOT.jar` ; `classes` seulement si la commande s'arrête avant `package` (`compile`, `test`) |
| H4 | `-rf :a -am` : `-am` ignoré (MNG-6863) → comme H1 | ✅ **Confirmée** (toujours vrai en 3.10.0) |
| H5 | Un SNAPSHOT déployé plus récemment à distance remplace le `b` installé localement, si la politique de mise à jour l'autorise | ✅ **Confirmée**, et l'inverse aussi : si l'install local est plus récent, il gagne |

## Tableau récapitulatif — ligne du log `-X` qui montre d'où vient `b`

Notations : `$WORK` = dossier de travail (dépôt local `$WORK/m2`, dépôt « distant » `file://$WORK/remote`),
`$PROJET` = `experiments/reactor-resolution/projet`. Avant chaque commande testée, `b/target/` contient
un `b` compilé **et** packagé dont la constante vaut `b-target` : si ce `b` était utilisé, on le verrait.

La colonne **A.class** est une seconde preuve, indépendante du log : `B.ORIGIN` est une constante de
compilation, javac la recopie dans `A.class`, on sait donc contre quel `b` le module `a` a été compilé.

| Commande | État avant la commande | `b` pris depuis | Ligne du log `-X` | A.class |
|---|---|---|---|---|
| `mvn clean package -pl a` | `b` **uniquement** dans `b/target` | ❌ nulle part → échec | `[ERROR] 	Could not find artifact exp:b:jar:1.0-SNAPSHOT in exp-remote (file://$WORK/remote)` | — |
| `mvn clean package -rf :a` | idem | ❌ nulle part → échec | `[ERROR] 	Could not find artifact exp:b:jar:1.0-SNAPSHOT in exp-remote (file://$WORK/remote): …` | — |
| `mvn clean package -pl a` | `b` installé en local (`depot-local`) + `b/target` plus récent (`b-target`) | dépôt local | `[DEBUG]  $WORK/m2/exp/b/1.0-SNAPSHOT/b-1.0-SNAPSHOT.jar` (bloc `Classpath:` du compiler de `a`) | `depot-local` |
| `mvn clean package -rf :a` | idem | dépôt local | `[DEBUG]  $WORK/m2/exp/b/1.0-SNAPSHOT/b-1.0-SNAPSHOT.jar` | `depot-local` |
| `mvn clean package -pl a` | `b` absent du dépôt local, déployé à distant (`depot-distant`) + `b/target` | dépôt distant | `[INFO] Downloaded from exp-remote: file://$WORK/remote/exp/b/1.0-SNAPSHOT/b-1.0-20261102.124346-1.jar` | `depot-distant` |
| `mvn clean package -rf :a` | idem | dépôt distant | `[INFO] Downloaded from exp-remote: file://$WORK/remote/exp/b/1.0-SNAPSHOT/b-1.0-20261102.124403-1.jar` | `depot-distant` |
| `mvn clean package -pl a -am` | sources de `b` = `source-courante`, dépôt local = `depot-local` | reactor : **jar** de `b/target` reconstruit dans la commande | `[DEBUG]  $PROJET/b/target/b-1.0-SNAPSHOT.jar` | `source-courante` |
| `mvn clean compile -pl a -am` | idem | reactor : `b/target/classes` de la commande | `[DEBUG]  $PROJET/b/target/classes` | `source-courante` |
| `mvn clean package -rf :a -am` | idem | dépôt local (`-am` ignoré, seul `a` est construit : `[INFO] Building a 1.0-SNAPSHOT`) | `[DEBUG]  $WORK/m2/exp/b/1.0-SNAPSHOT/b-1.0-SNAPSHOT.jar` | `depot-local` |
| `mvn clean package -pl a` (`updatePolicy=always`) | install local **puis** deploy distant plus récent | dépôt distant | `[INFO] Downloaded from exp-remote: file://$WORK/remote/exp/b/1.0-SNAPSHOT/b-1.0-20261102.124504-1.jar` | `distant-plus-recent` |
| `mvn clean package -pl a` (`updatePolicy=always`) | deploy distant **puis** install local plus récent | dépôt local (métadonnées distantes lues mais plus anciennes, aucun jar téléchargé) | `[INFO] Downloaded from exp-remote: …/exp/b/1.0-SNAPSHOT/maven-metadata.xml` puis `[DEBUG]  $WORK/m2/exp/b/1.0-SNAPSHOT/b-1.0-SNAPSHOT.jar` | `local-plus-recent` |
| `mvn clean package -pl a` (`updatePolicy=daily`) | v1 déjà récupérée aujourd'hui, puis v2 déployée | dépôt local (v1) : le distant n'est **pas** réinterrogé | `[DEBUG] Skipped remote request for exp:b:1.0-SNAPSHOT/maven-metadata.xml, locally cached metadata up-to-date` | `distant-v1` |
| `mvn clean package -pl a -U` (`updatePolicy=daily`) | idem | dépôt distant (v2) | `[INFO] Downloaded from exp-remote: file://$WORK/remote/exp/b/1.0-SNAPSHOT/b-1.0-20261102.124557-2.jar` | `distant-v2` |

Sortie complète du dernier passage : [`resultats-maven-3.10.0.txt`](resultats-maven-3.10.0.txt).

## Ce qu'il faut retenir

- **`-pl a` et `-rf :a` se comportent pareil** : seul `a` est dans le reactor, `b` est résolu comme
  n'importe quelle dépendance externe. `b/target/` est ignoré même s'il est plus récent que le dépôt local,
  et même s'il est le seul endroit où `b` existe (le build échoue alors).
- **Avec `-am`, le reactor gagne**, et la forme dépend de la phase atteinte par `b` dans la commande :
  le jar de `b/target` si `b` est passé par `package`, sinon `b/target/classes`.
- **`-rf :a -am` n'ajoute pas `b`** en 3.10.0 : pour reconstruire `b` en reprenant à `a`, il faut
  `-pl a -am` (ou `-rf :b`).
- **Pour un SNAPSHOT, « local » ne veut pas dire « prioritaire »** : Maven compare l'horodatage des
  métadonnées distantes avec celui de l'install local et prend le plus récent, mais seulement quand la
  politique de mise à jour l'autorise à interroger le distant (`daily` par défaut → `-U` pour forcer).
- Dans le bloc `Classpath:` du compiler, le chemin du dépôt local est toujours `…/b-1.0-SNAPSHOT.jar`,
  même quand le jar vient du distant (Maven copie le jar horodaté sous ce nom). La preuve d'une
  provenance distante est donc la ligne `Downloaded from exp-remote: …/b-1.0-<horodatage>-<n>.jar`.

## Pourquoi : `ReactorReader`

Le source de `ReactorReader` en 3.10.0 est identique à 3.9.10, à un détail près (un test sur le
classifier des artefacts `pom`). Il ne connaît que `session.getProjects()`, c'est-à-dire les modules
retenus après `-pl` / `-rf` / `-am`, et renvoie `null` pour les autres (→ résolution par les dépôts).
Pour un module du reactor, `find()` renvoie :

1. le fichier de l'artefact s'il a été produit dans la session (`hasArtifactFileFromPackagePhase`) → le jar ;
2. sinon, si la commande n'atteint pas `package`, `target/classes` (fallback « loose class files ») ;
3. sinon `null`.

## Procédure / reproduire

```bash
cd experiments/reactor-resolution
./run-tests.sh
```

Par défaut le script utilise `../../mvnw` (Maven 3.10.0) et travaille dans `./work` (ignoré par git) :
dépôt local `work/m2`, dépôt « distant » `file://work/remote`, logs `-X` complets dans `work/logs/`,
résumé dans `work/resultats.txt`. Variables : `MVN=/chemin/vers/mvn`, `WORK=/autre/dossier`.

Pour chaque scénario le script :

1. remet à zéro `exp:*` dans les dépôts et les `target/` du projet ;
2. prépare l'état (install local, deploy « CI », `package -pl b` pour remplir `b/target`) en changeant
   à chaque fois la constante `B.ORIGIN` pour marquer chaque version de `b` ;
   le deploy « CI » utilise son propre dépôt local, pour ne pas toucher celui du « dev » ;
3. lance la commande testée en `-X` et extrait : les modules construits, les lignes de résolution de
   `exp:b`, la ligne `b` du bloc `Classpath:` du compiler de `a`, et la constante trouvée dans `A.class`.

Note : lors de ce passage, `mvnw` n'a pas pu télécharger la distribution (Maven Central répondait
HTTP 429) ; la même archive `apache-maven-3.10.0-bin.tar.gz` a été prise sur `dlcdn.apache.org`
(SHA-512 vérifié) et passée via `MVN=…`.
