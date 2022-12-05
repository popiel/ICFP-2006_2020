(
    cat <<'FOO'
ohmega
bidirectional
ls
cat README
cat mult.spec
cat reverse.spec
cat raytrace.spec
cat aspects.spec
cat plus.2d
/bin/umodem mult.2d STOP
FOO
    cat mult.2d
    cat <<'FOO'
STOP
verify mult mult.2d
mail
/bin/umodem rev.2d STOP
FOO
    cat rev.2d
    cat <<'FOO'
STOP
verify rev rev.2d
exit
FOO
) |
java -jar ../../jvm/target/um.jar ../../codex.um | tee run.out
