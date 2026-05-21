#!/bin/sh
# Run by sh in the container to look for commands to run, which will be written the agent container to the workspace volume.
# Must use only portable POSIX sh features, as the container may be running any distribution, even BusyBox.
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
