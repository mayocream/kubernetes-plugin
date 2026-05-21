#!/bin/sh
set -x # TODO while developing
dir="$JENKINS_CONTAINER_WORK/$JENKINS_CONTAINER_NAME"
# TODO parallelize, write output & exit code, …
while true
do
  f=`ls -1 "$dir"/*/script.sh 2>&- | head -1`
  if test -n "$f"
  then
    sh "$f"
    rm "$f"
  else
    sleep 1
  fi
done
