set -x
while true
do
  until test -f "$LOC"
  do
    sleep 1
  done
  sh "$LOC"
  rm "$LOC"
done
