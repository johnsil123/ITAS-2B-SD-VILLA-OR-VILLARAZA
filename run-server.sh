#!/bin/sh
cd "$(dirname "$0")" && ./build.sh && java -cp out studycircle.server.ChatServer "$@"
