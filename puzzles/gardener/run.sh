(
    cat <<'FOO'
gardener
mathemantica
ls
cat README
exit
FOO
) |
java -jar ../../jvm/target/um.jar ../../codex.um | tee run.out
