# Lambda – build multi-modules, jar shadé et zip de livraison

Projet Maven multi-modules (Java 21, **Maven 3.10.0** via `./mvnw`) qui produit
un zip de livraison au nom fixe : `lambda-assembly/target/lambda-assembly-1.0.0-SNAPSHOT.zip`.
Le contenu de `lib/` dépend **uniquement du profil Maven actif** :

| Profil                         | Contenu de `lib/`                                                          |
|--------------------------------|----------------------------------------------------------------------------|
| `prod` (**actif par défaut**)  | `lambda-shade-1.0.0-SNAPSHOT.jar` + `annotations-26.1.0.jar` (promu)       |
| `dev` (`-Pdev`, postes de dev) | `lambda-1.0.0-SNAPSHOT.jar` + `jakarta.annotation-api-3.0.0.jar` + `annotations-26.1.0.jar` |

Dans les deux cas le zip contient aussi `bin/run.sh` / `bin/run.bat`
(`java -cp "lib/*" fr.antschw.lambda.Main`) et le comportement est identique.

## Modules

```
pom.xml            lambda-parent   : modules, versions des dépendances et des plugins, enforcer Maven 3.10.0+
lambda/            lambda          : lib Java simple (GreetingService, Main)
lambda-shade/      lambda-shade    : jar shadé de lambda (embarque / promeut ses dépendances)
lambda-assembly/   lambda-assembly : construit le zip (maven-assembly-plugin)
```

### `lambda`
Lib Java sans framework, avec deux dépendances qui n'ont elles-mêmes aucune dépendance :
- `jakarta.annotation:jakarta.annotation-api:3.0.0`
- `org.jetbrains:annotations:26.1.0`

### `lambda-shade`
Aucune source, une seule dépendance : `lambda`. Configuration du `maven-shade-plugin` :

```xml
<promoteTransitiveDependencies>true</promoteTransitiveDependencies>
<artifactSet>
    <includes>
        <include>fr.antschw.lambda:lambda</include>
        <include>jakarta.annotation:jakarta.annotation-api</include>
    </includes>
</artifactSet>
```

- **Embarqués** dans `lambda-shade-1.0.0-SNAPSHOT.jar` : `lambda` et `jakarta.annotation-api`.
- **Promu** : `org.jetbrains:annotations` n'est pas embarqué ; il devient une dépendance
  directe du `dependency-reduced-pom.xml`, le pom installé/publié pour ce module
  (c'est sa seule dépendance).

### `lambda-assembly`
Packaging `pom`, il ne produit que le zip. Chaque profil choisit sa dépendance **et**
son descripteur d'assembly (les scripts `bin/` sont mis en commun dans `common.xml`) :

```xml
<profile>
    <id>prod</id>
    <activation><activeByDefault>true</activeByDefault></activation>
    <properties><assembly.descriptor>src/main/assembly/prod.xml</assembly.descriptor></properties>
    <dependencies>
        <dependency><groupId>${project.groupId}</groupId><artifactId>lambda-shade</artifactId></dependency>
    </dependencies>
</profile>
<profile>
    <id>dev</id>
    <properties><assembly.descriptor>src/main/assembly/dev.xml</assembly.descriptor></properties>
    <dependencies>
        <dependency><groupId>${project.groupId}</groupId><artifactId>lambda</artifactId></dependency>
    </dependencies>
</profile>
```

`activeByDefault` est désactivé dès qu'un autre profil du même pom est activé :
`-Pdev` suffit, pas besoin de `-P!prod`.

#### Pourquoi deux descripteurs ?
Dans un build multi-modules, Maven (3.9 **comme 3.10.0**, vérifié) lit le `pom.xml`
d'origine de `lambda-shade` et non son dependency-reduced-pom. Sans précaution, le
zip prod contiendrait donc aussi `lambda` et `jakarta.annotation-api`, en double du
jar shadé. `prod.xml` exclut ces deux artefacts de `lib/` (ceux de l'`artifactSet`
du shade) ; `dev.xml` copie toutes les dépendances transitives, comme un projet normal.

Hors reactor (lambda-shade résolu depuis un dépôt), le pom réduit est bien utilisé et
seul `annotations` arrive par transitivité ; l'exclusion est alors sans effet.

## Build

```bash
./mvnw clean package          # prod (par défaut) : jar shadé
./mvnw clean package -Pdev    # dev : jar non shadé + dépendances transitives
```

Un `mvn` plus ancien que 3.10.0 est refusé par le `maven-enforcer-plugin`.

## Lancer la dist

```bash
unzip lambda-assembly/target/lambda-assembly-1.0.0-SNAPSHOT.zip
./lambda-assembly-1.0.0-SNAPSHOT/bin/run.sh antoine     # -> hello antoine
```

> Le warning `JAR will be empty` sur `lambda-shade` est normal : le module n'a pas
> de sources, son jar est ensuite remplacé par le jar shadé.
