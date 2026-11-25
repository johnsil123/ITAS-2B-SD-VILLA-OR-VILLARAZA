#!/bin/sh
# -sourcepath lets javac find every other class itself, so no file list (and no path-with-spaces problems).
cd "$(dirname "$0")" && mkdir -p out \
  && javac -encoding UTF-8 -d out -sourcepath src \
       src/studycircle/client/ClientApp.java src/studycircle/server/ChatServer.java \
  && echo "BUILD OK"
