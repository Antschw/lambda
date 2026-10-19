# Quarkus – build multi-modules + dist zip (lib shadée ou non selon le profil)

Projet Maven multi-modules qui produit **un zip exécutable** contenant un serveur
Quarkus REST (tuto officiel *Getting Started*, Java 21). Le zip a **toujours le
même nom** : `lambda-assembly/target/lambda-assembly-1.0.0-SNAPSHOT.zip`.
Seule la lib embarquée change selon le profil :

| Profil              | Lib dans `lib/main/`                                  |
|---------------------|-------------------------------------------------------|
| `dev` (par défaut)  | `fr.antschw.lambda.lambda-1.0.0-SNAPSHOT.jar` (non shadé) |
| `prod` (`-Pprod`)   | `fr.antschw.lambda.lambda-shade-1.0.0-SNAPSHOT.jar` (shadé) |

Le comportement du serveur est identique dans les deux cas.

## Modules

```
pom.xml            lambda-parent   : orchestre le build (<modules>), BOM Quarkus 3.40.1, versions des plugins
lambda/            lambda          : lib CDI (GreetingService) + index Jandex
lambda-shade/      lambda-shade    : reprend lambda dans un jar shadé + dependency-reduced-pom
lambda-assembly/   lambda-assembly : serveur Quarkus REST (GreetingResource) + zip (maven-assembly-plugin)
```

### `lambda` – la lib
- `GreetingService` est un bean CDI `@ApplicationScoped`.
- L'API CDI est en scope `provided` : Quarkus la fournit au runtime, elle n'est donc
  jamais embarquée (ni dans la lib, ni dans le jar shadé).
- `jandex-maven-plugin` génère `META-INF/jandex.idx` : c'est ce qui permet à Quarkus
  de découvrir le bean alors qu'il est dans un jar externe à l'application.

### `lambda-shade` – la lib shadée
- Aucune source, une seule dépendance : `lambda`.
- `maven-shade-plugin` fusionne `lambda` (et ses éventuelles dépendances) dans
  `lambda-shade-1.0.0-SNAPSHOT.jar`, qui remplace l'artefact principal
  (`shadedArtifactAttached=false`). L'index Jandex de `lambda` est conservé.
- `createDependencyReducedPom=true` : le pom installé/publié ne déclare plus les
  dépendances déjà embarquées.

### `lambda-assembly` – le serveur et le zip
- Application Quarkus standard : `quarkus-rest`, `quarkus-arc`, tests `@QuarkusTest`.
- `GreetingResource` injecte le `GreetingService` de la lib :
  - `GET /hello` → `Hello from Quarkus REST`
  - `GET /hello/greeting/{name}` → `hello {name}`
- En phase `package` : `quarkus-maven-plugin` construit `target/quarkus-app/`
  (layout fast-jar), puis `maven-assembly-plugin` le zippe avec `bin/start.sh` /
  `bin/start.bat` (descripteur `src/main/assembly/zip.xml`).
- **Les profils ne changent que la dépendance vers la lib** :

```xml
<profile>
    <id>dev</id>
    <activation><activeByDefault>true</activeByDefault></activation>
    <dependencies>
        <dependency><groupId>${project.groupId}</groupId><artifactId>lambda</artifactId></dependency>
    </dependencies>
</profile>
<profile>
    <id>prod</id>
    <dependencies>
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>lambda-shade</artifactId>
            <exclusions>
                <exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>
            </exclusions>
        </dependency>
    </dependencies>
</profile>
```

> Pourquoi l'exclusion `*:*` en prod ? Dans un build multi-modules, Maven lit le
> `pom.xml` d'origine de `lambda-shade` et non son dependency-reduced-pom. Sans
> l'exclusion, `lambda` serait embarqué **en plus** du jar shadé (vérifié).

## Build

Prérequis : Java 21, Maven 3.9+.

```bash
mvn clean package          # dev  : lib non shadée
mvn clean package -Pprod   # prod : lib shadée
```

## Lancer la dist

```bash
unzip lambda-assembly/target/lambda-assembly-1.0.0-SNAPSHOT.zip
./lambda-assembly-1.0.0-SNAPSHOT/bin/start.sh          # Windows : bin\start.bat
curl localhost:8080/hello/greeting/antoine              # -> hello antoine
```

Le port se change avec `-Dquarkus.http.port=9090` (passé à `start.sh`) ou via `JAVA_OPTS`.

Contenu du zip :

```
lambda-assembly-1.0.0-SNAPSHOT/
├── bin/start.sh, bin/start.bat
├── quarkus-run.jar
├── app/lambda-assembly-1.0.0-SNAPSHOT.jar
├── lib/boot/...
├── lib/main/...   (Quarkus + lambda OU lambda-shade selon le profil)
└── quarkus/...
```

> Le warning `JAR will be empty` sur `lambda-shade` est normal : le module n'a pas
> de sources, son jar est ensuite remplacé par le jar shadé.
