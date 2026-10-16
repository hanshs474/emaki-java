#!/bin/bash
# build.sh: compile, test, and lay out the signed Central bundle in build/central-bundle.zip (no Maven needed)
set -e
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-$HOME/sdk/jdk-17.0.20.1+1/Contents/Home}; export PATH=$JAVA_HOME/bin:$PATH
V=0.1.0; A=emaki; G=io/github/hanshs474
rm -rf build; mkdir -p build/classes build/test build/doc build/src
javac --release 11 -d build/classes $(find src/main -name '*.java')
javac --release 11 -cp build/classes -d build/test src/test/java/io/github/hanshs474/emaki/EmakiTest.java
java -cp build/classes:build/test io.github.hanshs474.emaki.EmakiTest
(cd build/classes && jar cf ../$A-$V.jar .)
(cd src/main/java && jar cf ../../../build/$A-$V-sources.jar .)
javadoc -quiet --release 11 -d build/doc -sourcepath src/main/java io.github.hanshs474.emaki
(cd build/doc && jar cf ../$A-$V-javadoc.jar .)
cp pom.xml build/$A-$V.pom
D=build/bundle/$G/$A/$V; mkdir -p $D
cp build/$A-$V.jar build/$A-$V-sources.jar build/$A-$V-javadoc.jar build/$A-$V.pom $D/
python3 ../_tools/sign-emaki.py $D/*.jar $D/*.pom
for f in $D/*.jar $D/*.pom; do printf '%s' "$(md5 -q $f)" > $f.md5; printf '%s' "$(shasum -a 1 $f | cut -d' ' -f1)" > $f.sha1; done
(cd build/bundle && zip -qr ../central-bundle.zip .)
ls $D
