#!/bin/sh
# Run by sh in the container to look for commands to run, which will be written the agent container to the workspace volume.
# Must use only portable POSIX sh features, as the container may be running any distribution, even BusyBox.
set -x # TODO while developing
dir="$JENKINS_CONTAINER_WORK/$JENKINS_CONTAINER_NAME"
# TODO parallelize
while true
do
  for procdir in "$dir"/*
  do
    if test -f "$procdir/script.sh" -a \! -f "$procdir/seen"
    then
      touch "$procdir/seen"
      sh "$procdir/script.sh" <&- >"$procdir/out.txt" 2>"$procdir/err.txt"
      echo $? >"$procdir/status.txt"
    fi
  done
  sleep 1
done
