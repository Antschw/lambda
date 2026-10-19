${project.artifactId} ${project.version}
Environnement : ${env}

lib/ contient le jar de la lambda pour cet environnement :
  - dev  : lambda-${project.version}.jar
  - prod : lambda-shade-${project.version}.jar
