#!/usr/bin/env bash
# Rejoue les scénarios de test « d'où Maven tire-t-il le module b quand on construit a ? ».
#
# Usage : ./run-tests.sh                (utilise ../../mvnw, donc Maven 3.10.0)
#         MVN=/chemin/mvn ./run-tests.sh
#         WORK=/tmp/exp ./run-tests.sh  (dépôt local, dépôt « distant » et logs isolés)
#
# Chaque build est lancé en -X ; les logs complets vont dans $WORK/logs/<scenario>.log
# et un résumé (preuves extraites des logs) est écrit dans $WORK/resultats.txt.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJET="$HERE/projet"
MVN="${MVN:-$HERE/../../mvnw}"
WORK="${WORK:-$HERE/work}"
LOGS="$WORK/logs"
RESULTS="$WORK/resultats.txt"
B_SRC="$PROJET/b/src/main/java/exp/b/B.java"

mkdir -p "$LOGS"
: > "$RESULTS"

# --- settings.xml dédiés : dépôt local isolé + dépôt « distant » file:// --------------------
# exp-remote ne sert que les SNAPSHOT ; central reste le dépôt des plugins.
write_settings() { # $1 = fichier, $2 = updatePolicy des snapshots
    cat > "$1" <<EOF
<settings>
  <localRepository>$WORK/m2</localRepository>
  <profiles>
    <profile>
      <id>exp</id>
      <repositories>
        <repository>
          <id>exp-remote</id>
          <url>file://$WORK/remote</url>
          <releases><enabled>false</enabled></releases>
          <snapshots><enabled>true</enabled><updatePolicy>$2</updatePolicy></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>exp</activeProfile></activeProfiles>
</settings>
EOF
}
write_settings "$WORK/settings-always.xml" always
write_settings "$WORK/settings-daily.xml" daily
SETTINGS="$WORK/settings-always.xml"

# --- helpers ----------------------------------------------------------------------------------
log() { echo "$*" | tee -a "$RESULTS"; }
tag() { case "$1" in "-pl a") echo pl-a ;; "-rf :a") echo rf-a ;; esac; }

# Remet à zéro tout ce qui concerne exp:* (les plugins restent en cache dans $WORK/m2).
reset() {
    rm -rf "$WORK/m2/exp" "$WORK/m2-ci/exp" "$WORK/remote"
    rm -rf "$PROJET/a/target" "$PROJET/b/target" "$PROJET/target"
    SETTINGS="$WORK/settings-always.xml"
}

# Change la constante B.ORIGIN, qui sera recopiée par javac dans A.class.
set_origin() { sed -i "s/\"B_ORIGIN=[^\"]*\"/\"B_ORIGIN=$1\"/" "$B_SRC"; }

# Build « silencieux » de préparation (pas la commande testée).
prep() { (cd "$PROJET" && "$MVN" -B -q -s "$SETTINGS" "$@") >> "$LOGS/prep.log" 2>&1 || { echo "PREP KO: $*"; exit 1; }; }

# Simule la CI : build + deploy de b avec SON PROPRE dépôt local (le dépôt local du dev n'est pas
# touché). maven.repo.local.tail ne sert qu'à réutiliser les plugins déjà téléchargés.
ci_deploy() {
    set_origin "$1"
    prep -Dmaven.repo.local="$WORK/m2-ci" -Dmaven.repo.local.tail="$WORK/m2" \
        clean deploy -pl b -am -DaltDeploymentRepository="exp-remote::file://$WORK/remote"
}

# Lance la commande testée en -X et extrait les preuves.
run() { # $1 = nom du scénario, reste = arguments mvn
    local name="$1"; shift
    local logf="$LOGS/$name.log"
    (cd "$PROJET" && "$MVN" -B -X -s "$SETTINGS" "$@") > "$logf" 2>&1
    local rc=$?
    log ""
    log "=== $name : mvn $* (exit $rc)"
    log "--- modules construits :"
    grep -E '^\[INFO\] Building [a-z]+ 1\.0-SNAPSHOT' "$logf" | tee -a "$RESULTS"
    log "--- résolution de exp:b (lignes du log) :"
    grep -E "^\[ERROR\] Failed to execute goal|^\[ERROR\][[:space:]]+Could not find artifact exp:b|Downloaded from exp-remote: .*/exp/b/1\.0-SNAPSHOT/(maven-metadata\.xml|b-[^/]*\.jar)|Could not find metadata exp:b:1\.0-SNAPSHOT/maven-metadata\.xml in exp-remote|Skipped remote request for exp:b:1\.0-SNAPSHOT/maven-metadata\.xml, locally cached" "$logf" \
        | cut -c1-200 | awk '!seen[$0]++' | tee -a "$RESULTS"
    log "--- classpath de compilation de a (ligne b) :"
    awk '/--- compiler:[^ ]*:compile \(default-compile\) @ a ---/{f=1} f&&/\[DEBUG\] Classpath:/{c=1;next} c&&/\[DEBUG\]  /{print;next} c{exit}' "$logf" \
        | grep -E '/b/|exp/b/' | tee -a "$RESULTS"
    log "--- B.ORIGIN recopié dans a/target/classes/exp/a/A.class :"
    if [ -f "$PROJET/a/target/classes/exp/a/A.class" ]; then
        grep -ao 'B_ORIGIN=[A-Za-z0-9-]*' "$PROJET/a/target/classes/exp/a/A.class" | tee -a "$RESULTS"
    else
        log "(A.class absent)"
    fi
}

# --- scénarios --------------------------------------------------------------------------------
echo "Maven : $("$MVN" -v 2>/dev/null | head -1)" | tee -a "$RESULTS"

# H1 – b seulement dans b/target (ni dépôt local, ni dépôt distant).
for opt in "-pl a" "-rf :a"; do
    reset
    set_origin b-target; prep clean package -pl b
    run "H1-target-seul_$(tag "$opt")" clean package $opt
done

# H1/H2 – b installé localement, puis b/target reconstruit avec un contenu différent.
for opt in "-pl a" "-rf :a"; do
    reset
    set_origin depot-local; prep clean install -pl b -am
    set_origin b-target;    prep clean package -pl b
    run "H2-depot-local_$(tag "$opt")" clean package $opt
done

# H2 – b absent du dépôt local mais présent sur le dépôt distant ; b/target existe.
for opt in "-pl a" "-rf :a"; do
    reset
    ci_deploy depot-distant
    set_origin b-target; prep clean package -pl b
    run "H2-depot-distant_$(tag "$opt")" clean package $opt
done

# H3 – -pl a -am : b dans le reactor (package puis compile pour comparer).
reset
set_origin depot-local; prep clean install -pl b -am
set_origin b-target;    prep clean package -pl b
set_origin source-courante
run "H3-am_package" clean package -pl a -am
run "H3-am_compile" clean compile -pl a -am

# H4 – -rf :a -am.
reset
set_origin depot-local; prep clean install -pl b -am
set_origin b-target;    prep clean package -pl b
set_origin source-courante
run "H4-rf-am" clean package -rf :a -am

# H5a – install local PUIS deploy distant plus récent (updatePolicy=always).
reset
set_origin depot-local; prep clean install -pl b -am
sleep 2; ci_deploy distant-plus-recent
set_origin b-target; prep clean package -pl b
run "H5a-distant-plus-recent" clean package -pl a

# H5b – deploy distant PUIS install local plus récent (updatePolicy=always).
reset
ci_deploy distant-ancien
sleep 2; set_origin local-plus-recent; prep clean install -pl b -am
set_origin b-target; prep clean package -pl b
run "H5b-local-plus-recent" clean package -pl a

# H5c – updatePolicy=daily : un nouveau deploy n'est vu qu'avec -U (ou le lendemain).
reset
SETTINGS="$WORK/settings-daily.xml"
ci_deploy distant-v1
run "H5c-1-daily-premier-build" clean package -pl a
sleep 2; ci_deploy distant-v2
set_origin b-target; prep clean package -pl b
run "H5c-2-daily-sans-U" clean package -pl a
run "H5c-3-daily-avec-U" clean package -pl a -U

set_origin source-initiale
echo
echo "Résumé : $RESULTS — logs complets : $LOGS"
