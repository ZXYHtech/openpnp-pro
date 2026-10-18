#!/bin/bash

platform='unknown'
unamestr=`uname`
case "$unamestr" in
	Linux)
		platform='linux'
		rootdir="$(dirname $(readlink -f $0))"
	;;
	Darwin)
		platform='mac'
		rootdir="$(cd $(dirname $0); pwd -P)"
	;;
esac

case "$platform" in
	mac)
java -Xdock:name=OpenPnP -Xms64m -Xmx1536m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED -jar $rootdir/target/openpnp-gui-2.6P3-beta62.jar
	;;
	linux)
java $1 -Xms64m -Xmx1536m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED -jar $rootdir/target/openpnp-gui-2.6P3-beta62.jar
	;;
esac
