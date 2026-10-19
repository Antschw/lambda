# Lambda Quarkus – build multi-modules + zip d'assembly

Projet Maven multi-modules qui produit un zip de livraison. Le contenu de `lib/`
dans le zip dépend du profil Maven :

| Profil              | Contenu de `lib/`                  |
|---------------------|------------------------------------|
| `dev` (par défaut)  | `lambda-1.0.0-SNAPSHOT.jar`        |
| `prod` (`-Pprod`)   | `lambda-shade-1.0.0-SNAPSHOT.jar`  |

## Modules

```
pom.xml            lambda-parent   : orchestre le build (<modules>), BOM Quarkus, versions des plugins
lambda/            lambda          : application Quarkus (extension quarkus-amazon-lambda)
lambda-shade/      lambda-shade    : reprend lambda + ses dépendances dans un seul jar (maven-shade-plugin)
lambda-assembly/   lambda-assembly : construit le zip (maven-assembly-plugin)
```

### `lambda`
Une lambda Quarkus minimale : `GreetingLambda` (handler `@Named("greeting")`,
déclaré dans `application.properties` via `quarkus.lambda.handler`) qui utilise
`GreetingService` injecté par CDI.

### `lambda-shade`
Aucune source. Une seule dépendance : `lambda`. Le `maven-shade-plugin` :
- fusionne `lambda` et toutes ses dépendances dans `lambda-shade-1.0.0-SNAPSHOT.jar`
  (`shadedArtifactAttached=false` : le jar shadé remplace l'artefact principal) ;
- génère un **dependency-reduced-pom** (`createDependencyReducedPom=true`) : c'est
  ce pom qui est installé/publié, sans les dépendances déjà embarquées. Un projet
  qui dépend de `lambda-shade` ne récupère donc aucune dépendance transitive.

### `lambda-assembly`
Packaging `pom` : il ne produit que le zip. Le descripteur
`src/main/assembly/zip.xml` est **unique** : il copie dans `lib/` les dépendances
**directes** du module (`useTransitiveDependencies=false`).

Ce sont les profils du `pom.xml` de ce module qui choisissent la dépendance :

```xml
<profile>
    <id>dev</id>
    <activation><activeByDefault>true</activeByDefault></activation>
    <properties><env>dev</env></properties>
    <dependencies>
        <dependency><groupId>${project.groupId}</groupId><artifactId>lambda</artifactId></dependency>
    </dependencies>
</profile>
<profile>
    <id>prod</id>
    <properties><env>prod</env></properties>
    <dependencies>
        <dependency><groupId>${project.groupId}</groupId><artifactId>lambda-shade</artifactId></dependency>
    </dependencies>
</profile>
```

`src/main/dist/` est copié à la racine du zip (avec filtrage des propriétés Maven) :
c'est là qu'on ajoute la configuration ou les scripts de la distribution.

## Build

Prérequis : Java 21, Maven 3.9+.

```bash
# dev (profil par défaut)
mvn clean package
unzip -l lambda-assembly/target/lambda-assembly-1.0.0-SNAPSHOT-dev.zip

# prod
mvn clean package -Pprod
unzip -l lambda-assembly/target/lambda-assembly-1.0.0-SNAPSHOT-prod.zip
```

Contenu du zip en dev :

```
lambda-assembly-1.0.0-SNAPSHOT-dev/
├── README.txt
└── lib/
    └── lambda-1.0.0-SNAPSHOT.jar
```

Contenu du zip en prod :

```
lambda-assembly-1.0.0-SNAPSHOT-prod/
├── README.txt
└── lib/
    └── lambda-shade-1.0.0-SNAPSHOT.jar
```

> Remarque : le warning `JAR will be empty` sur `lambda-shade` est normal, le
> module n'a pas de sources ; son jar est ensuite remplacé par le jar shadé.
>
> Le jar shadé sert ici à illustrer la structure : une application Quarkus a
> besoin de l'étape de build Quarkus (dossier `quarkus-app/` ou `function.zip`
> dans `lambda/target/`) pour démarrer réellement sur AWS Lambda.
